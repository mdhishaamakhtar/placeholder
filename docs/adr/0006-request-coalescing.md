# 0006. Coalesce concurrent cache misses

## Problem

A link gets posted somewhere big before any cache holds it, or right after an edit evicted it everywhere. Hundreds of requests arrive in the same millisecond, each misses the in-memory cache, and each asks Redis and then Postgres for the same row. That's a cache stampede, and it hits the database at exactly the moment traffic peaks.

## Decision

The in-memory layer is Caffeine's async cache, which coalesces by design: the first request for a slug starts the lookup and stores the *pending* result, and every concurrent request for the same slug awaits that same lookup. The lookup runs in a background scope, not in the request that started it, so one client disconnecting doesn't fail the others. A lookup that finds nothing completes with null, which Caffeine doesn't keep.

(An earlier version hand-wrote this with a map of in-flight lookups, including the case of the first request being cancelled. Caffeine handles all of it.)

## Consequences

- A cold viral link reaches Postgres about once per server, not once per request. Measured in [the benchmarks](../system-design.md#benchmarks).
- Coalescing is per server, not global. With N servers a cold burst still makes up to N lookups. A global version (a Redis lock per slug) would add a round trip to every miss to save N - 1 cheap primary-key lookups: not worth it.
