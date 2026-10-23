# 0002. Three cache layers, kept honest by pub/sub eviction

## Problem

Redirects are most of the traffic and must be fast. Snips are small and read far more often than written. But links with an owner can be edited, so a stale cache would serve the old content or the old destination.

## Decision

Reads go through, in order:

1. **Caffeine, in each server's memory.** 50,000 entries, expire 10 minutes after last access. A hit costs no network call at all.
2. **Redis, shared.** 24 hours, or until the snip expires if sooner. Also holds short-lived "this slug doesn't exist" markers, so repeated requests for a dead link don't reach Postgres.
3. **Postgres**, the source of truth.

An edit writes Postgres, deletes the Redis key, and publishes `snip:<slug>` on a Redis pub/sub channel. Every server evicts that slug from its own memory when the message arrives (the server that made the edit evicts locally first, in case Redis is down).

HTTP caching adds a fourth layer outside the servers: snips nobody owns can never change, so they're `immutable` to browsers and CDNs. Owned snips are revalidated with an ETag carrying their version, which costs a `304`.

## Consequences

- Pub/sub is fire-and-forget. A server that's briefly disconnected from Redis misses evictions, and serves the old version until its 10-minute access expiry runs out. That's the bound on staleness, and it's an accepted trade: the alternative (checking a version on every read) gives up the point of the in-memory layer.
- Redis is optional. If it's down, reads fall through to Postgres, and the in-memory layer still absorbs the hot set. Measured in [the failure experiments](../system-design.md#failure-modes).
- **Revisit when** edits become common enough that 10-minute staleness matters: replace pub/sub with a Kafka topic of edits each server reads from (so a reconnecting server replays what it missed), or shorten the local expiry.
