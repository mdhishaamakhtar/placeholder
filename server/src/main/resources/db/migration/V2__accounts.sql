CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,  -- stored lowercased
    password_hash TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Only a SHA-256 of each session token is stored, so a database leak doesn't hand out live sessions.
CREATE TABLE sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_id ON sessions (user_id);
CREATE INDEX sessions_expires_at ON sessions (expires_at);

ALTER TABLE snips
    ADD COLUMN owner_id   UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN version    INTEGER NOT NULL DEFAULT 1;

-- "Your links", newest first, paged by created_at.
CREATE INDEX snips_owner_created ON snips (owner_id, created_at DESC, slug DESC) WHERE owner_id IS NOT NULL;
-- The cleanup job's scan.
CREATE INDEX snips_expires_at ON snips (expires_at) WHERE expires_at IS NOT NULL;
