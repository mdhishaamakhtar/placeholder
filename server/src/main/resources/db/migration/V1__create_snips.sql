CREATE TABLE snips (
    slug       VARCHAR(16)  PRIMARY KEY,
    kind       VARCHAR(8)   NOT NULL,
    language   VARCHAR(32),
    content    TEXT         NOT NULL,
    lines      INTEGER      NOT NULL,
    bytes      INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
