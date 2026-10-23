# 0007. No Bloom filter for missing slugs, yet

## Problem

Someone scanning for links requests random slugs. Each one misses every cache and costs a Postgres lookup, and the "doesn't exist" marker never helps because no slug repeats. The benchmarks measure exactly this (`missing-flood`).

## Options

- **A Bloom filter of every slug that exists**, checked before any lookup. A "no" is certain, so most of a scan never reaches Postgres. But a filter must never say "no" to a slug that exists, or a real link breaks. A per-server filter fed by pub/sub would miss creations during a disconnect; one in the cache Redis can be evicted; one in Postgres is a lookup again.
- **Rate-limit misses per client.** Cheap, and stops a single scanner. Doesn't stop a distributed one.
- **Nothing**, and size Postgres for it. A miss is a primary-key lookup on an index that fits in memory.

## Decision

Nothing for now. The benchmark shows what a scan costs, and it's well within what Postgres serves while the hot path stays in memory.

## Consequences

- **Revisit when** misses become a real share of Postgres load. The safe design then is a per-server Bloom filter rebuilt from Postgres on start and kept current from a Kafka topic of creations (not pub/sub, which can miss messages), plus a per-client miss limit.
