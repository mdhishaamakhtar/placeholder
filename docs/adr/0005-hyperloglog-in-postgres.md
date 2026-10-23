# 0005. Unique visitors as HyperLogLog sketches stored in Postgres

## Problem

"How many different people opened this link in the last 30 days" can't be answered from daily unique counts: someone who visits on three days would be counted three times. Exact answers need the visitor list itself, which is both large and a privacy problem.

## Options

- **Store every (link, day, visitor).** Exact, and grows with traffic.
- **Redis `PFADD`/`PFCOUNT`.** Redis has HyperLogLog built in. But the cache Redis evicts and doesn't persist, so counts would vanish, and it would make the cache Redis a store of record, which it deliberately isn't here ([0002](0002-three-cache-layers.md)).
- **HyperLogLog sketches in Postgres** (Apache DataSketches). A sketch estimates how many distinct values went into it, sketches merge, and a sketch can't be read back into the values.

## Decision

Sketches in Postgres, one per link per UTC day in `click_days.visitors`, updated in the same transaction as the visit count. Unique visitors over a range is the union of that range's sketches. Settings: 2¹² registers, 4 bits each, about 1.6% standard error and at most about 2.6 KB per link per day (small sketches are stored as a sparse list, so a link with a few visitors costs a few bytes).

The visitor id going into a sketch is an HMAC of IP address and user agent under a server secret: raw IPs are never stored, and without the key the id can't be linked back to an address.

## Consequences

- Unique visitors are estimates. Tested: 8,000 real visitors over two days are reported within 3%.
- The visit count itself is exact; only the "different people" number is approximate.
- Rollups are durable and need nothing but Postgres to read.
