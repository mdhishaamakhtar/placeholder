# 0004. Exactly-once counting from at-least-once delivery

## Problem

Queues deliver at least once, never exactly once. A consumer can commit a batch to Postgres and then die before committing its Kafka offsets, and the batch is delivered again, to whichever consumer gets its partitions. A rebalance in the middle of a batch does the same. Naive counting would count those clicks twice.

Kafka's own exactly-once support doesn't help here: it covers reading from and writing to Kafka, and this write goes to Postgres.

## Decision

The server that sees a visit gives the click a random UUID, and that id travels with it everywhere. Postgres keeps every counted id for a week in `click_events` (primary key `id`). Ingesting a batch is one transaction that:

1. selects which of the batch's ids are already in `click_events` and drops them,
2. inserts the rest,
3. adds them to the per-day rollups in `click_days`.

A consumer commits its Kafka offsets only after that transaction commits. So every failure ends up as a redelivery, and every redelivery is recognised and skipped. If two writers race on the same ids, the loser's insert hits the primary key, the transaction is retried, and on the retry the ids are already there.

Rollup rows are locked in a fixed order (by slug, then day), so two consumers whose batches overlap can't deadlock.

## Consequences

- Exactly-once counting, verified end to end: the failure experiments check that every redirect served was counted exactly once, through Redis and Postgres outages and a killed server.
- `click_events` costs about 100 bytes a click (row and both indexes) for a week. At 100 million clicks a day that's around 70 GB, which is where this design changes. Kafka allows a leaner version of the same guarantee: store each partition's consumed offset in Postgres, in the same transaction as the rollups, and on startup seek each partition to the stored offset instead of Kafka's. A redelivered batch then starts before the stored offset and is skipped by position, with no per-click ids kept at all. It wasn't done from the start because it puts offset handling (seeking on every partition assignment) into the app's code, and at this scale the id table is cheap.
