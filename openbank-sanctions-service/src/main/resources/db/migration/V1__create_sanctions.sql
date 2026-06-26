CREATE TABLE IF NOT EXISTS sanctions_checks (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    entity_type      VARCHAR(20) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    aliases          JSONB NOT NULL DEFAULT '[]',
    date_of_birth    VARCHAR(10),
    nationality      CHAR(2),
    identifiers      JSONB NOT NULL DEFAULT '{}',
    status           VARCHAR(20) NOT NULL DEFAULT 'CLEAR',
    matches          JSONB NOT NULL DEFAULT '[]',
    overall_score    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    checked_lists    JSONB NOT NULL DEFAULT '[]',
    reviewed_by      VARCHAR(100),
    review_note      TEXT,
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at      TIMESTAMPTZ
);
CREATE INDEX idx_sanctions_status ON sanctions_checks(status);
CREATE INDEX idx_sanctions_name   ON sanctions_checks(name);
CREATE INDEX idx_sanctions_score  ON sanctions_checks(overall_score DESC);
