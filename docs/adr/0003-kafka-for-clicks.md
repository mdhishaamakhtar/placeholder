# 0003. Clicks travel through Kafka

## Problem

Owners want to know how often their links are opened. That makes every redirect, the hottest and fastest path, also a write. Writing to Postgres on the request would tie redirect latency to Postgres and multiply its write load by the read traffic.

## Options

- **Write on the request.** Simplest, and wrong for the reasons above.
- **Buffer in each server, flush to Postgres.** Takes the write off the request, but a Postgres outage either loses clicks or grows the buffer until the server runs out of memory, and a crash loses whatever was buffered.
- **A log between the servers and Postgres.** Takes the write off the request *and* holds clicks durably while Postgres is down. The candidates were a Redis Stream (this project already runs Redis) and Kafka.

## What happened

The first version used a Redis Stream on a second, durable Redis. It worked, and the failure experiments passed, but it needed a hand-written producer (a bounded buffer, batching, a linger timer, fallback when Redis was down) and a hand-written consumer (reconnects, re-reading its own unacknowledged entries, periodically claiming a dead consumer's entries, parsing `XINFO` for lag). The first benchmark found a bug in that producer: it always waited out its linger timer, which capped each server at about 4,500 clicks a second, and **185,048 clicks were dropped** under load. Consumers also fell 325,000 entries behind; any of them could get any link's clicks, so they likely fought over the same hot link's row.

Every one of those parts is something Kafka's client already does, tested by everyone who uses it.

## Decision

Kafka, with the plain client (no framework on top).

- **Producer.** Each server's `KafkaProducer` batches (`linger.ms` 20, 64 KB batches), compresses (lz4) and retries on its own thread. `acks=all` with idempotence, so its retries can't write a click twice. `max.block.ms=0`, so a send never blocks the request: if the buffer is full, the click is dropped and counted instead. And `metadata.recovery.strategy=none`, for the reason below.
- **Topic.** `clicks`, 12 partitions, **keyed by slug**: all of one link's clicks go to one partition, so one consumer owns that link's rollup row and consumers never contend for it. Kept for 7 days, so rollups can be rebuilt by replaying. Created explicitly by the app; the broker doesn't auto-create topics.
- **Consumer group.** Every server runs one consumer in group `rollup`. Kafka splits the partitions between them and moves a member's partitions to the others when it leaves or stops heartbeating (10 s). Offsets are committed only after the batch's Postgres transaction commits ([0004](0004-exactly-once-counting.md)).

When something fails:

| Kafka | Postgres | What happens |
|---|---|---|
| up | up | Normal: producer → topic → consumer group → Postgres. |
| up | down | Clicks wait in Kafka (for days, if need be). Consumers rewind to the start of the failed batch and retry with backoff. |
| down | either | Each producer holds clicks in memory (32 MB) for up to 2 minutes and sends them when Kafka is back. Past that, clicks are dropped and counted. Redirects are unaffected either way. |

### A trap the failure experiments caught

The first Kafka run lost **20,593 of 60,000 visits** when Kafka was unreachable for 10 seconds: everything sent during the outage, with zero retries. Kafka 4's client, on finding no broker reachable, by default discards its cluster metadata and bootstraps again ("rebootstrap"). A producer that has forgotten a topic's partitions can't append to its buffer, and with `max.block.ms=0` it fails the send at once instead of waiting. Never-block plus rebootstrap is silent loss. With `metadata.recovery.strategy=none` the producer keeps what it knew, buffers through the outage, and the same experiment counts all 60,000.

The trade: rebootstrap exists for a cluster whose brokers have all been replaced at new addresses. Without it, the producer needs a restart in that case. A rolling broker replacement (the normal way) doesn't trigger it.

## Results

Same benchmark, same laptop: **0 clicks dropped** out of 895,649, all counted exactly once, peak backlog 31,000 instead of 325,000.

The plumbing went from 333 lines to 291. That's fewer, but not by much: a correct consumer still has to rewind on a failed batch, survive a commit that fails mid-rebalance, and own a thread (a Kafka consumer can't be shared across threads). The real gain is that batching, compression, retries, failover and lag metrics are now configuration of tested code, not code we maintain.

## Consequences

- One more system to run. Locally it's a single broker that is also its own controller (KRaft, no ZooKeeper). In production it's three brokers with replication factor 3, so a broker can fail without losing clicks.
- The "stream down, write straight to Postgres" fallback is gone. Kafka's producer buffer covers short outages; it's simpler and the experiments show it's enough.
- A server killed outright loses the clicks in its producer buffer: at most the last 20 ms of its traffic.
- Kafka makes more possible later: a second consumer group (fraud detection, a warehouse loader) can read the same clicks independently.
