# 0001. Random slugs, not a counter or a hash

## Problem

Every link needs a short, unique slug, minted by whichever of N stateless servers took the request.

## Options

- **A counter** (one sequence in Postgres, or ranges handed out to each server, or Snowflake-style IDs). Unique by construction and dense, so slugs stay short. But consecutive slugs reveal how many links exist and let anyone walk them, which is a privacy leak for pastes. Range allocation and Snowflake also need coordination or per-server IDs.
- **A hash of the content.** Same paste, same slug, for free. But two people pasting the same thing would share one link, so neither could edit or expire it alone, and a truncated hash collides anyway, so you still need a collision check.
- **Random.** 7 characters from a 57-symbol alphabet (no `0/O`, `1/l/I`) give 57⁷ ≈ 1.95 × 10¹² slugs. Each server generates them with `SecureRandom`, with no coordination at all.

## Decision

Random. The insert is `INSERT … ON CONFLICT DO NOTHING`; if the slug was taken, roll again (up to 5 times).

## Consequences

- Collisions grow as the table fills. With n links stored, an insert collides with probability n / 1.95 × 10¹². At 365 million links (a million a day for a year) that's about 1 in 5,000 inserts needing a second try. At 20 billion links it's about 1%, still cheap. The slug length is the knob, and it's one constant in `shared/Rules.kt`.
- Slugs can't be guessed or enumerated, so an unlisted paste stays unlisted.
- **Revisit when** the table holds about 10¹¹ links (5% retries): go to 8 characters, which existing links don't notice.
