-- Raw journal read-cache for ADR-0352 shadow input capture. It never moves money.
-- Origin is the authenticated writer's UNVERIFIED assertion, not an IR EventKind.
ALTER TABLE journal_entries
    ADD COLUMN origin_kind VARCHAR(40),
    ADD COLUMN origin_record_id UUID,
    ADD COLUMN origin_submitter TEXT,
    ADD COLUMN origin_rail VARCHAR(32),
    ADD COLUMN origin_instruction_type VARCHAR(32),
    ADD COLUMN origin_reversal_of_transaction_id UUID,
    ADD CONSTRAINT ck_journal_origin_presence CHECK (
        (origin_kind IS NULL AND origin_record_id IS NULL AND origin_submitter IS NULL
         AND origin_rail IS NULL AND origin_instruction_type IS NULL AND origin_reversal_of_transaction_id IS NULL)
        OR (origin_kind IS NOT NULL AND origin_kind IN ('PAYMENT_TRANSACTION', 'BILLING_FEE', 'INTEREST_CAPITALIZATION', 'OTHER')
            AND origin_record_id IS NOT NULL AND origin_submitter IS NOT NULL AND btrim(origin_submitter) <> '')
    );

CREATE TABLE journal_history_snapshots (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL,
    from_date DATE NOT NULL,
    through_date DATE NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    database_view TEXT NOT NULL,
    opening_booked NUMERIC NOT NULL,
    closing_booked NUMERIC NOT NULL,
    entry_count BIGINT NOT NULL,
    missing_origin_count BIGINT NOT NULL,
    records_sha256 VARCHAR(64) NOT NULL,
    persisted BOOLEAN NOT NULL DEFAULT true CHECK (persisted),
    CHECK (from_date <= through_date),
    CHECK (entry_count >= 0 AND missing_origin_count >= 0 AND missing_origin_count <= entry_count)
);
CREATE TABLE journal_history_snapshot_entries (
    snapshot_id UUID NOT NULL REFERENCES journal_history_snapshots(id) ON DELETE CASCADE,
    ordinal BIGINT NOT NULL CHECK (ordinal >= 1),
    canonical_json JSONB NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal)
);
-- Includes opening-anchor contributors as well as window records. A retained read-cache must not
-- outlive any source fact from which its opening balance or window was derived.
CREATE TABLE journal_history_snapshot_sources (
    snapshot_id UUID NOT NULL REFERENCES journal_history_snapshots(id) ON DELETE CASCADE,
    journal_id UUID NOT NULL,
    entry_date DATE NOT NULL,
    PRIMARY KEY (snapshot_id, journal_id, entry_date)
);
CREATE INDEX idx_history_snapshot_sources_journal ON journal_history_snapshot_sources(journal_id, entry_date);

-- Row-level erasure of a source atomically removes every derived read-cache, including its manifest.
-- Partition DETACH/DROP does not fire row triggers: the partition maintainer and every snapshot read
-- must additionally reject/invalidate caches whose recorded source is no longer attached.
CREATE FUNCTION invalidate_journal_history_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM journal_history_snapshots WHERE id IN (
        SELECT snapshot_id FROM journal_history_snapshot_sources
        WHERE journal_id = OLD.id AND entry_date = OLD.entry_date
    );
    IF TG_OP = 'UPDATE' THEN RETURN NEW; END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER invalidate_journal_history_before_delete
    BEFORE DELETE ON journal_entries FOR EACH ROW EXECUTE FUNCTION invalidate_journal_history_source();

-- Rollback: first disable the additive snapshot route. Drop the three read-cache tables (children
-- first), trigger/function and optional origin columns only in a reviewed migration. Preserve source
-- journals/outbox and legal holds. There is no independent retention duration or destructive cleanup
-- of financial source records in this migration.

-- Immutability permits deletion for source lifecycle, never mutation of a captured result.
CREATE FUNCTION reject_journal_history_snapshot_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'journal history snapshots are immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER history_manifest_no_update BEFORE UPDATE ON journal_history_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_journal_history_snapshot_update();
CREATE TRIGGER history_entries_no_update BEFORE UPDATE ON journal_history_snapshot_entries
    FOR EACH ROW EXECUTE FUNCTION reject_journal_history_snapshot_update();
CREATE TRIGGER history_sources_no_update BEFORE UPDATE ON journal_history_snapshot_sources
    FOR EACH ROW EXECUTE FUNCTION reject_journal_history_snapshot_update();

-- Source-line erasure and origin-evidence erasure also remove derived copies, not just parent rows.
CREATE FUNCTION invalidate_journal_history_line_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM journal_history_snapshots WHERE id IN (
        SELECT snapshot_id FROM journal_history_snapshot_sources WHERE journal_id = OLD.journal_id
    );
    IF TG_OP = 'UPDATE' THEN RETURN NEW; END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER invalidate_journal_history_line_before_delete
    BEFORE DELETE ON journal_lines FOR EACH ROW EXECUTE FUNCTION invalidate_journal_history_line_source();
CREATE TRIGGER invalidate_journal_history_line_before_update
    BEFORE UPDATE ON journal_lines FOR EACH ROW EXECUTE FUNCTION invalidate_journal_history_line_source();
CREATE TRIGGER invalidate_journal_history_origin_before_update
    BEFORE UPDATE OF origin_kind, origin_record_id, origin_submitter, origin_rail,
        origin_instruction_type, origin_reversal_of_transaction_id ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION invalidate_journal_history_source();
