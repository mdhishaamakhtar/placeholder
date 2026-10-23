# Decision records

Each record is one decision: the problem, what was chosen, what was given up, and what would make it worth revisiting. They're short on purpose. [The design document](../system-design.md) shows how the pieces fit together.

| # | Decision |
|---|---|
| [0001](0001-random-slugs.md) | Random slugs, not a counter or a hash |
| [0002](0002-three-cache-layers.md) | Three cache layers, kept honest by pub/sub eviction |
| [0003](0003-kafka-for-clicks.md) | Clicks travel through Kafka |
| [0004](0004-exactly-once-counting.md) | Exactly-once counting from at-least-once delivery |
| [0005](0005-hyperloglog-in-postgres.md) | Unique visitors as HyperLogLog sketches stored in Postgres |
| [0006](0006-request-coalescing.md) | Coalesce concurrent cache misses |
| [0007](0007-no-bloom-filter.md) | No Bloom filter for missing slugs, yet |
| [0008](0008-one-deployable.md) | One deployable, not services |
