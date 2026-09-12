-- Durable customer recertification evidence, additive after V16. The table is separate from the grant:
-- confirming a review never changes capabilities, status or lifecycle_revision.
--
-- A cycle is tied to one exact lifecycle revision. A later suspend/revoke/reinstate makes an old
-- pending cycle ineligible for confirmation instead of allowing a stale "keep" click to reopen it.
--
-- Rollback: deploy older images first; they ignore this additive table. Preserve the records for
-- audit. If removal is required, add a new forward migration; never edit this migration.
CREATE TABLE delegation_recertification_cycles (
    id                          UUID PRIMARY KEY,
    delegation_id               UUID NOT NULL REFERENCES delegation_grants(id),
    grantor_party_id            UUID NOT NULL,
    expected_lifecycle_revision BIGINT NOT NULL,
    audience                    VARCHAR(16) NOT NULL,
    sequence                    INTEGER NOT NULL,
    due_at                      TIMESTAMPTZ NOT NULL,
    status                      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at                  TIMESTAMPTZ NOT NULL,
    confirmed_at                TIMESTAMPTZ,
    confirmed_by                UUID,
    CONSTRAINT uq_delegation_recertification_cycle
        UNIQUE (delegation_id, expected_lifecycle_revision, sequence),
    CONSTRAINT chk_delegation_recertification_audience
        CHECK (audience IN ('PERSONAL', 'FOP', 'SME', 'CORPORATE')),
    CONSTRAINT chk_delegation_recertification_status
        CHECK (status IN ('PENDING', 'CONFIRMED')),
    CONSTRAINT chk_delegation_recertification_sequence CHECK (sequence > 0),
    CONSTRAINT chk_delegation_recertification_evidence CHECK (
        (status = 'PENDING' AND confirmed_at IS NULL AND confirmed_by IS NULL)
        OR
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL)
    ),
    CONSTRAINT chk_delegation_recertification_time_order
        CHECK (confirmed_at IS NULL OR confirmed_at >= created_at)
);

CREATE INDEX idx_delegation_recertification_pending_grantor
    ON delegation_recertification_cycles (grantor_party_id, status, due_at);
CREATE INDEX idx_delegation_recertification_grant_revision
    ON delegation_recertification_cycles (delegation_id, expected_lifecycle_revision, sequence DESC);

GRANT ALL ON delegation_recertification_cycles TO openbank;
