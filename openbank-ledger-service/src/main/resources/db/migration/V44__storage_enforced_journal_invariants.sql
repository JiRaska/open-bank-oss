-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0372: the database itself refuses an unbalanced posted journal and any rewrite of a posted
-- journal or its lines. The domain model (JournalEntry.validateBalance, reversal-only correction)
-- stays the first check; this is the second, and it also binds writers that never load the domain
-- model: a migration, a repair script, a new repository method, a manual session.
--
-- "Posted" below means status <> 'PENDING', i.e. POSTED or REVERSED.
--
-- 1. BALANCE AT COMMIT. Deferred constraint triggers re-add the lines of every journal a
--    transaction touched and fail the COMMIT if a posted journal does not net to zero per
--    settlement currency. The encoding is the domain's: side 'D'/'C' over base_amount, grouped by
--    base_currency (not amount/currency_code, which is the instructed currency and legitimately
--    differs across the legs of an FX posting). Deferred because the service inserts the journal
--    row, already POSTED, before its lines. Constraint triggers are row-level by definition and
--    cannot use transition tables, so the check runs once per row written: one index range scan
--    over idx_journal_lines_journal_id each, i.e. quadratic in the lines of ONE journal and linear
--    in journals. A per-transaction "already checked" memo was considered and left out: the only
--    place to keep one is a setting any writer can forge, which would be an unlogged way around a
--    check that deliberately has no override.
--
-- 2. APPEND-ONLY ONCE POSTED. Measured over src/main on the day this was written, the service
--    issues exactly ONE update against these two tables and no delete:
--      PanacheJournalRepository.saveReversal: status POSTED -> REVERSED, version = version + 1.
--    Journals are inserted already POSTED (PENDING exists only in memory), lines are insert-only.
--    So a posted journal row accepts that one transition and nothing else, a REVERSED row accepts
--    nothing, and a line of a posted journal accepts neither UPDATE nor DELETE. The comparison is
--    over the whole row, so a column added later is protected without editing this function.
--    A PENDING journal and its lines stay freely editable; POSTED never goes back to PENDING.
--    TRUNCATE of either table is refused the same way.
--
-- 3. A REPAIR IS EXPLICIT AND LOGGED. A transaction that must rewrite a posted row first calls
--      SELECT ledger_declare_repair('why, and the issue or change that authorises it');
--    which sets two transaction-local settings: the reason and the id of the declaring
--    transaction. The guards let the change through only while the current transaction is that
--    one, and write the reason, the operation and the row identity to ledger_repair_audit, which
--    is itself append-only with no override. Binding to the transaction id is deliberate: a
--    session-level SET on a pooled connection would otherwise switch the control off for every
--    later request served by that connection. The balance check has NO override.
--
-- Limits, stated so nobody reads more into this than it gives:
--  * It guards against mistakes and unreviewed code. A superuser, the table owner (ALTER TABLE ...
--    DISABLE TRIGGER, DROP TRIGGER) or session_replication_role = replica bypasses it. Tamper
--    evidence for that case stays with the closed-period anchors.
--  * Partition DETACH / DROP and TRUNCATE of a single partition fire no trigger on the parent.
--  * It does not refuse appending further lines to an already posted journal in a LATER
--    transaction as long as the journal still balances; journal_lines carries no reference that
--    would tell the two cases apart without a per-insert lookup.
--  * Journals that were already unbalanced before this migration are not touched and not
--    re-checked until something writes to them; the block at the end reports how many exist.
--
-- Existing rows are not rewritten. Online-safe: CREATE TRIGGER takes a brief SHARE ROW EXCLUSIVE
-- lock on each table and partition, and the closing report is one aggregate scan of journal_lines.
--
-- Rollback (leaves the data as it is; keep ledger_repair_audit, it is evidence):
--   DROP TRIGGER journal_lines_balanced_at_commit ON journal_lines;
--   DROP TRIGGER journal_entries_balanced_on_insert ON journal_entries;
--   DROP TRIGGER journal_entries_balanced_on_posting ON journal_entries;
--   DROP TRIGGER journal_entries_append_only ON journal_entries;
--   DROP TRIGGER journal_lines_append_only ON journal_lines;
--   DROP TRIGGER journal_entries_no_truncate ON journal_entries;
--   DROP TRIGGER journal_lines_no_truncate ON journal_lines;
--   DROP FUNCTION enforce_journal_balance_from_line(), enforce_journal_balance_from_entry(),
--     assert_posted_journal_balanced(UUID), guard_posted_journal_entry(),
--     guard_posted_journal_line(), guard_journal_truncate();
--   -- ledger_declare_repair / ledger_active_repair_reason / ledger_repair_audit may stay.

CREATE TABLE ledger_repair_audit (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    transaction_xid TEXT        NOT NULL DEFAULT pg_current_xact_id()::text,
    database_role   TEXT        NOT NULL DEFAULT session_user,
    reason          TEXT        NOT NULL,
    table_name      TEXT        NOT NULL,
    operation       TEXT        NOT NULL,
    journal_id      UUID,
    entry_date      DATE,
    line_id         UUID,
    changed_columns TEXT[],

    CONSTRAINT ck_ledger_repair_audit_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_ledger_repair_audit_table CHECK (table_name IN ('journal_entries', 'journal_lines')),
    CONSTRAINT ck_ledger_repair_audit_operation CHECK (operation IN ('UPDATE', 'DELETE', 'TRUNCATE'))
);
CREATE INDEX idx_ledger_repair_audit_journal ON ledger_repair_audit (journal_id);

COMMENT ON TABLE ledger_repair_audit IS
    'ADR-0372: one row per posted journal row or line rewritten under ledger_declare_repair(). Append-only; identifiers and a reason, never row contents.';

CREATE FUNCTION reject_ledger_repair_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_repair_audit is append-only: % refused', TG_OP USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER ledger_repair_audit_append_only
    BEFORE UPDATE OR DELETE ON ledger_repair_audit
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_repair_audit_mutation();
CREATE TRIGGER ledger_repair_audit_no_truncate
    BEFORE TRUNCATE ON ledger_repair_audit
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_repair_audit_mutation();

-- ---------------------------------------------------------------------------------------------
-- The override: declared per transaction, never per session.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ledger_declare_repair(reason TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF reason IS NULL OR length(btrim(reason)) < 12 THEN
        RAISE EXCEPTION 'ledger_declare_repair needs a reason of at least 12 characters'
            USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('openbank.ledger_repair_reason', btrim(reason), true);
    PERFORM set_config('openbank.ledger_repair_xid', pg_current_xact_id()::text, true);
END;
$$;

COMMENT ON FUNCTION ledger_declare_repair(TEXT) IS
    'ADR-0372: declares that the CURRENT transaction rewrites posted journal rows, with the reason. Every rewritten row is logged to ledger_repair_audit. Has no effect outside an explicit transaction.';

CREATE FUNCTION ledger_active_repair_reason() RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    declared_reason TEXT := NULLIF(btrim(current_setting('openbank.ledger_repair_reason', true)), '');
    declared_xid    TEXT := NULLIF(current_setting('openbank.ledger_repair_xid', true), '');
BEGIN
    IF declared_reason IS NULL OR declared_xid IS NULL OR declared_xid <> pg_current_xact_id()::text THEN
        RETURN NULL;
    END IF;
    RETURN declared_reason;
END;
$$;

-- ---------------------------------------------------------------------------------------------
-- Append-only once posted.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION guard_posted_journal_entry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    repair_reason TEXT;
    changed       TEXT[];
BEGIN
    IF OLD.status = 'PENDING' AND (TG_OP = 'DELETE' OR NEW.status IN ('PENDING', 'POSTED')) THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;

    -- The one lifecycle transition the service performs on a posted row (saveReversal).
    IF TG_OP = 'UPDATE'
        AND OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
        AND NEW.version = OLD.version + 1
        AND (to_jsonb(NEW) - 'status' - 'version') = (to_jsonb(OLD) - 'status' - 'version') THEN
        RETURN NEW;
    END IF;

    repair_reason := ledger_active_repair_reason();
    IF repair_reason IS NULL THEN
        RAISE EXCEPTION 'journal % (%) is append-only: % refused', OLD.id, OLD.status, TG_OP
            USING ERRCODE = '55000',
                  HINT = 'Correct a posted journal with a reversal. A reviewed repair calls ledger_declare_repair(reason) in the same transaction (ADR-0372).';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        SELECT array_agg(o.key ORDER BY o.key) INTO changed
        FROM jsonb_each(to_jsonb(OLD)) o
        WHERE to_jsonb(NEW) -> o.key IS DISTINCT FROM o.value;
    END IF;
    INSERT INTO ledger_repair_audit (reason, table_name, operation, journal_id, entry_date, changed_columns)
    VALUES (repair_reason, 'journal_entries', TG_OP, OLD.id, OLD.entry_date, changed);

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

-- Row triggers on the partitioned parent are cloned to every partition, present and future.
CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION guard_posted_journal_entry();

CREATE FUNCTION guard_posted_journal_line() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    repair_reason TEXT;
    changed       TEXT[];
    editable      BOOLEAN;
BEGIN
    -- Editable only while every journal involved exists and is still PENDING. A line whose journal
    -- row is missing is treated as posted: an orphan is evidence, not scratch data.
    SELECT count(*) > 0 AND bool_and(j.status = 'PENDING') INTO editable
    FROM journal_entries j WHERE j.id = OLD.journal_id;
    IF editable AND TG_OP = 'UPDATE' AND NEW.journal_id IS DISTINCT FROM OLD.journal_id THEN
        SELECT count(*) > 0 AND bool_and(j.status = 'PENDING') INTO editable
        FROM journal_entries j WHERE j.id = NEW.journal_id;
    END IF;
    IF editable THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;

    repair_reason := ledger_active_repair_reason();
    IF repair_reason IS NULL THEN
        RAISE EXCEPTION 'line % belongs to posted journal % and is append-only: % refused',
            OLD.id, OLD.journal_id, TG_OP
            USING ERRCODE = '55000',
                  HINT = 'Correct a posted journal with a reversal. A reviewed repair calls ledger_declare_repair(reason) in the same transaction (ADR-0372).';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        SELECT array_agg(o.key ORDER BY o.key) INTO changed
        FROM jsonb_each(to_jsonb(OLD)) o
        WHERE to_jsonb(NEW) -> o.key IS DISTINCT FROM o.value;
    END IF;
    INSERT INTO ledger_repair_audit (reason, table_name, operation, journal_id, line_id, changed_columns)
    VALUES (repair_reason, 'journal_lines', TG_OP, OLD.journal_id, OLD.id, changed);

    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_lines_append_only
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION guard_posted_journal_line();

CREATE FUNCTION guard_journal_truncate() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    repair_reason TEXT := ledger_active_repair_reason();
BEGIN
    IF repair_reason IS NULL THEN
        RAISE EXCEPTION '% is append-only: TRUNCATE refused', TG_TABLE_NAME
            USING ERRCODE = '55000',
                  HINT = 'A reviewed repair calls ledger_declare_repair(reason) in the same transaction (ADR-0372).';
    END IF;
    INSERT INTO ledger_repair_audit (reason, table_name, operation)
    VALUES (repair_reason, TG_TABLE_NAME, 'TRUNCATE');
    RETURN NULL;
END;
$$;

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION guard_journal_truncate();
CREATE TRIGGER journal_lines_no_truncate
    BEFORE TRUNCATE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION guard_journal_truncate();

-- ---------------------------------------------------------------------------------------------
-- Balance at commit.
-- ---------------------------------------------------------------------------------------------
-- Lines first, journal second: a balanced set of lines needs no look at journal_entries at all,
-- which is every posting the service makes. journal_lines has no entry_date, so the status lookup
-- cannot prune partitions and is paid only on the way to a refusal (or for a PENDING draft).
CREATE FUNCTION assert_posted_journal_balanced(checked_journal_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    unbalanced RECORD;
BEGIN
    SELECT l.base_currency,
           coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'D'), 0) AS debits,
           coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'C'), 0) AS credits
    INTO unbalanced
    FROM journal_lines l
    WHERE l.journal_id = checked_journal_id
    GROUP BY l.base_currency
    HAVING coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'D'), 0)
        <> coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'C'), 0)
    LIMIT 1;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM journal_entries j WHERE j.id = checked_journal_id AND j.status <> 'PENDING') THEN
        RETURN;
    END IF;
    RAISE EXCEPTION 'posted journal % is not balanced in %: debits=% credits=%',
        checked_journal_id, unbalanced.base_currency, unbalanced.debits, unbalanced.credits
        USING ERRCODE = '23514', CONSTRAINT = 'journal_balanced_per_currency';
END;
$$;

CREATE FUNCTION enforce_journal_balance_from_line() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM assert_posted_journal_balanced(OLD.journal_id);
    END IF;
    IF TG_OP = 'INSERT' OR (TG_OP = 'UPDATE' AND NEW.journal_id IS DISTINCT FROM OLD.journal_id) THEN
        PERFORM assert_posted_journal_balanced(NEW.journal_id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION enforce_journal_balance_from_entry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_posted_journal_balanced(NEW.id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_lines_balanced_at_commit
    AFTER INSERT OR UPDATE OR DELETE ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_journal_balance_from_line();

-- A journal row that arrives posted, and one that becomes posted. POSTED -> REVERSED is left out
-- on purpose: the lines cannot have changed, and re-checking there would make a journal that was
-- already unbalanced before this migration impossible to reverse.
CREATE CONSTRAINT TRIGGER journal_entries_balanced_on_insert
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status <> 'PENDING')
    EXECUTE FUNCTION enforce_journal_balance_from_entry();
CREATE CONSTRAINT TRIGGER journal_entries_balanced_on_posting
    AFTER UPDATE ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status = 'PENDING' AND NEW.status <> 'PENDING')
    EXECUTE FUNCTION enforce_journal_balance_from_entry();

-- ---------------------------------------------------------------------------------------------
-- History is reported, not rewritten and not validated (ADR-0372: NOT VALID for history).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    unbalanced_count BIGINT;
BEGIN
    SELECT count(DISTINCT b.journal_id) INTO unbalanced_count
    FROM (
        SELECT l.journal_id
        FROM journal_lines l
        GROUP BY l.journal_id, l.base_currency
        HAVING coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'D'), 0)
            <> coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'C'), 0)
    ) b
    WHERE EXISTS (SELECT 1 FROM journal_entries j WHERE j.id = b.journal_id AND j.status <> 'PENDING');
    IF unbalanced_count > 0 THEN
        RAISE WARNING 'ADR-0372: % posted journal(s) were already unbalanced before V44; they are not rewritten and need their own issue', unbalanced_count;
    ELSE
        RAISE NOTICE 'ADR-0372: no posted journal was unbalanced before V44';
    END IF;
END;
$$;
