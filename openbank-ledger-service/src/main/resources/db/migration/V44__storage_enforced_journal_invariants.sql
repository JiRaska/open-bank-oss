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
-- 1. BALANCE AT COMMIT. Deferred constraint triggers fail the COMMIT if a posted journal has
--    fewer than two lines, lacks a debit or a credit side, or does not net to zero per settlement
--    currency. The encoding is the domain's: side 'D'/'C' over base_amount, grouped by
--    base_currency (not amount/currency_code, which is the instructed currency and legitimately
--    differs across the legs of an FX posting). Deferred because the service inserts the journal
--    row, already POSTED, before its lines.
--    Constraint triggers are row-level by definition and cannot use transition tables, so every
--    written line raises its own event. To keep a wide journal linear, a line's INSERT event hands
--    the check on when it can PROVE a later event of the same trigger will see the same journal:
--    it looks at the two heap slots a following insert normally lands in, and skips only if one
--    holds a line of the same journal written by this transaction under a later command id. That
--    line's event is queued behind this one, so whenever this one fires, that one fires after it.
--    The proof uses nothing a writer can set (tuple xmin, cmin and xmax, not a setting), and it
--    holds if constraints are forced early with SET CONSTRAINTS: the last line written always
--    checks. Where the proof is not available the full check runs: UPDATE and DELETE events,
--    lines of one multi-row statement (same command id, so that shape stays quadratic), a
--    successor placed elsewhere in the heap, and the first sixteen lines of any journal, for which
--    adding the lines up is cheaper than probing for a successor.
--    Skipping on "the journal row was written by this transaction" alone was tried and is NOT
--    sound: after SET CONSTRAINTS ALL IMMEDIATE the journal's own event has already fired, and a
--    line added afterwards would have been waved through.
--
-- 2. APPEND-ONLY ONCE POSTED. Measured over src/main on the day this was written, the service
--    issues exactly ONE update against these two tables and no delete:
--      PanacheJournalRepository.saveReversal: status POSTED -> REVERSED, version = version + 1.
--    Journals are inserted already POSTED (PENDING exists only in memory), lines are insert-only.
--    So a posted journal row accepts that one transition and nothing else, a REVERSED row accepts
--    nothing, and a line of a posted journal accepts neither UPDATE nor DELETE, from the statement
--    that inserted it on, not only after commit. The comparison is over the whole row, so a column
--    added later is protected without editing this function.
--    A journal and all of its lines are written in ONE transaction, so a line may be INSERTED only
--    into a PENDING journal or into a POSTED journal whose row was written by the current
--    transaction (its xmin is in progress, which for a row this session can see means "mine",
--    subtransactions included). A line for a journal that does not exist is refused too: it could
--    be adopted by a journal inserted later. Marking a journal REVERSED also rewrites its row in
--    the current transaction, which is why the test is "written by me AND still POSTED": the
--    reversal's lines can never land on the original again (the defect V10 and V13 repaired).
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
--  * A wide journal inserted by ONE multi-row statement is still checked once per line.
--  * INSERT ... ON CONFLICT DO NOTHING into journal_lines does not make a replay harmless: BEFORE
--    triggers fire before the conflict is resolved, so re-inserting the lines of a journal an
--    earlier transaction posted is refused. Key an idempotent seed on the journal row instead.
--  * Inside the transaction that inserts a journal, a second line is accepted on the strength of
--    a sibling line this transaction wrote, without re-reading the journal row.
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
--   DROP TRIGGER journal_lines_insert_into_own_journal ON journal_lines;
--   DROP TRIGGER journal_entries_no_truncate ON journal_entries;
--   DROP TRIGGER journal_lines_no_truncate ON journal_lines;
--   DROP FUNCTION enforce_journal_balance_from_line(), enforce_journal_balance_from_entry(),
--     assert_posted_journal_balanced(UUID), guard_posted_journal_entry(),
--     guard_posted_journal_line(), guard_journal_line_insert(), guard_journal_truncate(),
--     ledger_written_by_current_transaction(xid);
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
    CONSTRAINT ck_ledger_repair_audit_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'))
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

-- True when the row version carrying this xmin was written by the current transaction or one of
-- its subtransactions. A row this session can see was either committed or written by itself, so
-- "its xmin is still in progress" means "mine"; comparing with pg_current_xact_id() alone would
-- miss a row written inside a savepoint. xmin is 32 bits wide: the epoch is taken from the
-- current transaction id and corrected by one where the two straddle a wraparound.
CREATE FUNCTION ledger_written_by_current_transaction(row_xmin xid) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE
    current_xid BIGINT := pg_current_xact_id()::text::bigint;
    candidate   BIGINT := ((current_xid >> 32) << 32) + row_xmin::text::bigint;
BEGIN
    IF candidate - current_xid > 2147483648 THEN
        candidate := candidate - 4294967296;
    ELSIF current_xid - candidate > 2147483648 THEN
        candidate := candidate + 4294967296;
    END IF;
    IF candidate < 3 THEN
        RETURN FALSE;
    END IF;
    RETURN coalesce(pg_xact_status(candidate::text::xid8) = 'in progress', FALSE);
END;
$$;

CREATE FUNCTION guard_journal_line_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    journal_count BIGINT;
    all_pending   BOOLEAN;
    all_own       BOOLEAN;
    repair_reason TEXT;
BEGIN
    -- A repair transaction logs every line it adds to a posted journal, its own journals included:
    -- a journal row rewritten under the repair also carries this transaction's xmin.
    IF coalesce(current_setting('openbank.ledger_repair_xid', true), '') <> '' THEN
        repair_reason := ledger_active_repair_reason();
    END IF;

    -- Second and later lines: a sibling line written by this transaction already passed this guard
    -- here, so the journal row (one probe per partition, with no date to prune by) need not be read
    -- again. journal_lines is not partitioned and the probe stops at the first row.
    IF repair_reason IS NULL AND EXISTS (
        SELECT 1 FROM journal_lines sibling
        WHERE sibling.journal_id = NEW.journal_id
          AND (sibling.xmin = pg_current_xact_id()::xid OR ledger_written_by_current_transaction(sibling.xmin))
    ) THEN
        RETURN NEW;
    END IF;

    SELECT count(*),
           coalesce(bool_and(j.status = 'PENDING'), FALSE),
           coalesce(bool_and(j.status = 'PENDING'
               OR (j.status = 'POSTED' AND (j.xmin = pg_current_xact_id()::xid
                                            OR ledger_written_by_current_transaction(j.xmin)))), FALSE)
    INTO journal_count, all_pending, all_own
    FROM journal_entries j WHERE j.id = NEW.journal_id;

    IF journal_count > 0 AND all_pending THEN
        RETURN NEW;
    END IF;
    IF repair_reason IS NOT NULL THEN
        INSERT INTO ledger_repair_audit (reason, table_name, operation, journal_id, line_id)
        VALUES (repair_reason, 'journal_lines', 'INSERT', NEW.journal_id, NEW.id);
        RETURN NEW;
    END IF;
    IF journal_count > 0 AND all_own THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'line % cannot join journal %: a journal and its lines are append-only and written in one transaction',
        NEW.id, NEW.journal_id
        USING ERRCODE = '55000',
              HINT = 'Insert the journal row first and its lines in the same transaction. A reviewed repair calls ledger_declare_repair(reason) in the same transaction (ADR-0372).';
END;
$$;

CREATE TRIGGER journal_lines_insert_into_own_journal
    BEFORE INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION guard_journal_line_insert();

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
-- Lines first, journal second: a well-formed set of lines needs no look at journal_entries at all,
-- which is every posting the service makes. journal_lines has no entry_date, so the status lookup
-- cannot prune partitions and is paid only on the way to a refusal (or for a PENDING draft).
CREATE FUNCTION assert_posted_journal_balanced(checked_journal_id UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    line_count BIGINT;
    has_debit  BOOLEAN;
    has_credit BOOLEAN;
    off_currency TEXT;
    last_currency TEXT;
    off_debits   NUMERIC;
    off_credits  NUMERIC;
BEGIN
    -- One flat pass; only a journal with lines in more than one settlement currency needs the
    -- per-currency grouping.
    SELECT count(*),
           count(*) FILTER (WHERE l.side = 'D') > 0,
           count(*) FILTER (WHERE l.side = 'C') > 0,
           min(l.base_currency),
           max(l.base_currency),
           coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'D'), 0),
           coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'C'), 0)
    INTO line_count, has_debit, has_credit, off_currency, last_currency, off_debits, off_credits
    FROM journal_lines l
    WHERE l.journal_id = checked_journal_id;
    IF off_currency IS DISTINCT FROM last_currency THEN
        SELECT g.base_currency, g.debits, g.credits INTO off_currency, off_debits, off_credits
        FROM (
            SELECT l.base_currency,
                   coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'D'), 0) AS debits,
                   coalesce(sum(l.base_amount) FILTER (WHERE l.side = 'C'), 0) AS credits
            FROM journal_lines l
            WHERE l.journal_id = checked_journal_id
            GROUP BY l.base_currency
        ) g
        WHERE g.debits <> g.credits
        ORDER BY g.base_currency
        LIMIT 1;
        IF NOT FOUND THEN
            off_currency := NULL;
        END IF;
    ELSIF off_debits = off_credits THEN
        off_currency := NULL;
    END IF;
    IF line_count >= 2 AND has_debit AND has_credit AND off_currency IS NULL THEN
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM journal_entries j WHERE j.id = checked_journal_id AND j.status <> 'PENDING') THEN
        RETURN;
    END IF;
    IF line_count < 2 THEN
        RAISE EXCEPTION 'posted journal % has fewer than two lines (%)', checked_journal_id, line_count
            USING ERRCODE = '23514', CONSTRAINT = 'journal_has_two_sided_lines';
    END IF;
    IF NOT (has_debit AND has_credit) THEN
        RAISE EXCEPTION 'posted journal % needs both a debit and a credit line', checked_journal_id
            USING ERRCODE = '23514', CONSTRAINT = 'journal_has_two_sided_lines';
    END IF;
    RAISE EXCEPTION 'posted journal % is not balanced in %: debits=% credits=%',
        checked_journal_id, off_currency, off_debits, off_credits
        USING ERRCODE = '23514', CONSTRAINT = 'journal_balanced_per_currency';
END;
$$;

CREATE FUNCTION enforce_journal_balance_from_line() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    own_tid   TID;
    own_cmin  BIGINT;
    own_xmax  TEXT;
    own_block BIGINT;
    own_slot  INT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        PERFORM assert_posted_journal_balanced(OLD.journal_id);
        IF TG_OP = 'UPDATE' AND NEW.journal_id IS DISTINCT FROM OLD.journal_id THEN
            PERFORM assert_posted_journal_balanced(NEW.journal_id);
        END IF;
        RETURN NULL;
    END IF;

    -- Hand the check on only to a line whose own event is provably queued behind this one: same
    -- journal, written by this transaction, under a LATER command id, never touched since (a set
    -- xmax would make cmin a combo id). Looked for in the next slot of this page and the first of
    -- the next page; anywhere else it is simply not found and the full check runs here.
    -- Only worth trying past the first lines: a narrow journal is cheaper to add up than to probe.
    -- sequence is caller-supplied and decides nothing but which of two sound paths runs.
    IF NEW.sequence > 16 THEN
        SELECT l.ctid, l.cmin::text::bigint, l.xmax::text INTO own_tid, own_cmin, own_xmax
        FROM journal_lines l WHERE l.id = NEW.id;
    END IF;
    IF own_xmax = '0' THEN
        own_block := split_part(btrim(own_tid::text, '()'), ',', 1)::bigint;
        own_slot  := split_part(btrim(own_tid::text, '()'), ',', 2)::int;
        IF EXISTS (
            SELECT 1 FROM journal_lines next_line
            WHERE next_line.ctid = ANY (ARRAY[
                      format('(%s,%s)', own_block, own_slot + 1)::tid,
                      format('(%s,1)', own_block + 1)::tid])
              AND next_line.journal_id = NEW.journal_id
              AND next_line.xmax::text = '0'
              AND next_line.cmin::text::bigint > own_cmin
              AND (next_line.xmin = pg_current_xact_id()::xid
                   OR ledger_written_by_current_transaction(next_line.xmin))
        ) THEN
            RETURN NULL;
        END IF;
    END IF;

    PERFORM assert_posted_journal_balanced(NEW.journal_id);
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
