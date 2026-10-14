-- #12425: aggregate-only technical source snapshots. These are not statutory approvals.
-- Additive reader-first migration. Never rewrite a captured payload or request receipt.
-- Rollback before first write: drop the two tables and guard function. After a write, retain
-- evidence and roll back only application readers/writers; destructive DB rollback needs a
-- separate reviewed retention and reconciliation decision.
CREATE TABLE pension_participant_snapshots (
    snapshot_id UUID PRIMARY KEY,
    company_id UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    payload_schema_version TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, period_start, period_end, revision)
);

CREATE INDEX pension_participant_snapshots_period_idx
    ON pension_participant_snapshots (company_id, period_start, period_end, revision DESC);

CREATE TABLE pension_participant_snapshot_receipts (
    scope_hash CHAR(64) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES pension_participant_snapshots (snapshot_id),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION pension_reject_snapshot_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'participant snapshot evidence is immutable';
END;
$$;

CREATE TRIGGER pension_participant_snapshots_immutable
    BEFORE UPDATE OR DELETE ON pension_participant_snapshots
    FOR EACH ROW EXECUTE FUNCTION pension_reject_snapshot_change();
CREATE TRIGGER pension_participant_snapshots_no_truncate
    BEFORE TRUNCATE ON pension_participant_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION pension_reject_snapshot_change();
CREATE TRIGGER pension_participant_snapshot_receipts_immutable
    BEFORE UPDATE OR DELETE ON pension_participant_snapshot_receipts
    FOR EACH ROW EXECUTE FUNCTION pension_reject_snapshot_change();
CREATE TRIGGER pension_participant_snapshot_receipts_no_truncate
    BEFORE TRUNCATE ON pension_participant_snapshot_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION pension_reject_snapshot_change();
