-- ADR-0031 D5: externally-signed audit anchors (tamper-evidence beyond internal consistency).
-- The V5 hash chain proves INTERNAL consistency, but a wholesale rewrite of audit_entries
-- recomputes a self-consistent chain and would still pass GET /api/v1/audit/integrity. A periodic,
-- externally-signed checkpoint over the chain head defeats this: the signature is verifiable
-- against a key the service does not hold at rest, so a rewritten history no longer matches a
-- previously-signed checkpoint. Verified via GET /api/v1/audit/anchors/verify.
-- Rollback: DROP TABLE audit_anchor; DROP SEQUENCE audit_anchor_seq;

CREATE TABLE IF NOT EXISTS audit_anchor (
    id                BIGSERIAL PRIMARY KEY,
    last_entry_id     UUID,
    last_record_hash  CHAR(64),
    chained_count     BIGINT       NOT NULL,
    chain_status      VARCHAR(20)  NOT NULL,
    anchor_digest     CHAR(64)     NOT NULL,
    signature         TEXT,
    key_id            VARCHAR(100) NOT NULL,
    signed_at         TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_anchor_signed_at ON audit_anchor(signed_at DESC);

-- PanacheEntity allocates ids from "<table>_seq" INCREMENT BY 50 (see V4); generation:none means
-- the sequence must exist or every INSERT fails with relation "audit_anchor_seq" does not exist.
CREATE SEQUENCE IF NOT EXISTS audit_anchor_seq INCREMENT BY 50;

-- Anchors are immutable evidence, exactly like audit_entries (EBA ICT): no UPDATE / no DELETE.
CREATE OR REPLACE RULE no_update_audit_anchor AS ON UPDATE TO audit_anchor DO INSTEAD NOTHING;
CREATE OR REPLACE RULE no_delete_audit_anchor AS ON DELETE TO audit_anchor DO INSTEAD NOTHING;

COMMENT ON TABLE audit_anchor IS
    'ADR-0031 D5: externally-signed tamper-evidence checkpoints over the audit hash chain';

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
