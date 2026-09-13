-- Durable, strictly ordered hand-off for DORA ICT incident lifecycle events.
-- Existing incident rows start at revision 1. The context projector remains disabled until every
-- producer pod runs this schema/code pair, so mixed-version records cannot become authoritative.
-- Rollback before any V6 code has written data: DROP TABLE ict_incident_outbox;
-- DROP SEQUENCE ict_incident_outbox_seq; ALTER TABLE ict_incidents DROP COLUMN aggregate_revision;

ALTER TABLE ict_incidents
    ADD COLUMN aggregate_revision BIGINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT chk_ict_incidents_aggregate_revision CHECK (aggregate_revision > 0);

CREATE TABLE ict_incident_outbox (
    id                  BIGINT       PRIMARY KEY,
    event_id            UUID         NOT NULL UNIQUE,
    aggregate_id        UUID         NOT NULL,
    aggregate_revision  BIGINT       NOT NULL,
    event_type          VARCHAR(128) NOT NULL,
    payload             TEXT         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    attempt_count       INTEGER      NOT NULL DEFAULT 0,
    sent_at             TIMESTAMPTZ,
    last_error          TEXT,
    synthetic           BOOLEAN      NOT NULL DEFAULT FALSE,
    claimed_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_ict_incident_outbox_revision UNIQUE (aggregate_id, aggregate_revision),
    CONSTRAINT chk_ict_incident_outbox_revision CHECK (aggregate_revision > 0)
);

CREATE SEQUENCE ict_incident_outbox_seq INCREMENT BY 50;
CREATE INDEX idx_ict_incident_outbox_status_created
    ON ict_incident_outbox (status, created_at ASC);
CREATE INDEX idx_ict_incident_outbox_aggregate
    ON ict_incident_outbox (aggregate_id, aggregate_revision);
