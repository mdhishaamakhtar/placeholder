# 0009. A dead-letter topic for clicks that can't be counted

## Problem

The click consumer retries a failed batch with backoff until it succeeds ([0004](0004-exactly-once-counting.md)). That is right when Postgres is down: the clicks wait in Kafka and are counted when it returns. It is wrong when the batch itself is the problem: a rollup row whose sketch bytes can't be read, a click that violates a constraint, a bug that throws on one record. No retry fixes those, so the consumer would try forever, and every click behind the bad one on that partition would never be counted. Nothing in the failure experiments covers this, because none of the dependencies has to fail for it to happen.

## Options

- **Retry forever**, as before. Simple, and correct for outages. One bad record stalls a twelfth of all clicks for good, and the only sign is a growing consumer lag.
- **Give up after N attempts and skip the batch.** Unblocks the partition, but throws away the healthy clicks in the same batch with the bad one, and the bad one is gone without a trace.
- **A dead-letter topic.** When a batch fails for a reason that won't change, take it one click at a time: count the ones that succeed on their own, send the ones that fail to `clicks.dead` as they arrived, with the reason in a header, and commit. The partition moves on, nothing healthy is lost, and the bad clicks are kept, with why, for someone to look at and replay once the cause is fixed.
- **A dead-letter table in Postgres.** The same, but the bad record lands next to the data it failed against. If Postgres is what's refusing the record, the write to the table may fail for the same reason.

## Decision

A dead-letter topic, with the failure sorted first. A `SQLException` whose SQLSTATE class is about the connection or the moment (`08` connection, `40` rollback, `53` resources, `57` operator intervention, `58` system) or that the driver marks transient is **retried forever**, as is a pool timeout. Anything else, including failures that aren't SQL at all, is **quarantined**: the batch is counted one click at a time, and each click that fails on its own goes to `clicks.dead`. If the dead-letter send itself fails, or a transient failure happens mid-quarantine, the whole batch is retried later as before.

`clicks_dropped_total{reason="poison"}` counts what was set aside. It should stay at zero; a non-zero value is a bug report with the record attached.

## Consequences

- One bad record can no longer stall a partition. The test `aPoisonedRollupRowIsDeadLetteredAndThePartitionMovesOn` corrupts a rollup row, queues clicks for it ahead of clicks for a healthy link on the same partition, and checks that the healthy ones are counted and the others arrive on the dead-letter topic.
- Quarantine is slow (one transaction per click) and that is fine: it is the rare path, and a batch is at most a thousand clicks.
- A transient failure in the middle of a quarantine replays the batch, so a click may reach `clicks.dead` twice. Its id is in the message, so a replay tool deduplicates the same way ingest does.
- The sorting of failures is by SQLSTATE, which is Postgres-specific in practice. Moving to another database means checking those classes.
- **Revisit when** anything actually lands on the topic. The first real dead letter will say whether the reason header is enough to act on, or whether a small admin command to replay the topic is worth writing.
