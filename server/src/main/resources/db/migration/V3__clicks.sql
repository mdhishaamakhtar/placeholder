-- Every visit the pipeline has counted, kept for a week. The id is minted by the app server that saw the visit,
-- so when the queue delivers a batch twice (it's at-least-once), the second copy is recognised here and skipped.
CREATE TABLE click_events (
    id   UUID        PRIMARY KEY,
    slug VARCHAR(16) NOT NULL,
    at   TIMESTAMPTZ NOT NULL
);
-- The weekly prune's scan.
CREATE INDEX click_events_at ON click_events (at);

-- One row per link per UTC day: how many visits, and a HyperLogLog sketch of who made them. Sketches merge,
-- so unique visitors over any range of days is the union of that range's sketches, without storing a visitor list.
CREATE TABLE click_days (
    slug     VARCHAR(16) NOT NULL,
    day      DATE        NOT NULL,
    visits   BIGINT      NOT NULL,
    visitors BYTEA       NOT NULL,
    PRIMARY KEY (slug, day)
);
