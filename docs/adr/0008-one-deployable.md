# 0008. One deployable, not services

## Problem

Redirects, the API, the web app, preview cards and the click consumers have different load profiles. They could be separate services, scaled independently.

## Decision

One deployable. Every server runs everything, and capacity is added by adding identical servers. The parts that need to scale independently already can: the click consumers are a Kafka consumer group, so more servers means more consumers (up to the topic's 12 partitions), and Postgres and Redis are separate tiers.

## Consequences

- One build, one image, one thing to deploy and roll back. No network hops or versioned contracts between parts of the same app, and request and response types are shared with the browser through one Kotlin module.
- A spike in one kind of work (say, preview-card rendering) competes for CPU with redirects on the same servers. **Revisit when** that shows up in the latency of the hot path: the first thing to split out would be the click consumers (a deployment that runs only them), which needs a config flag, not a new codebase.
