-- #12473: append-only calculated correction evidence and obligation drafts. These rows are NOT
-- payout instructions or confirmations. Publication stays disabled until the application commits
-- the complete journal, unit/cash accounting and external instruction lifecycle atomically.
-- Rollback: DROP TRIGGERs, DROP FUNCTION reject_nav_correction_draft_mutation(), DROP TABLE
-- nav_correction_obligation_drafts, DROP TABLE nav_correction_journal, DROP INDEXes below.
-- Rolling back after writes destroys correction evidence and requires fund-accounting approval.

-- Composite references make the original transaction belong to the recorded NAV/contract/fund,
-- and the replacement NAV actually correct the original in that same fund.
CREATE UNIQUE INDEX uq_nav_correction_parent_identity
    ON fund_navs (id, corrects_nav_id, fund_id);
CREATE UNIQUE INDEX uq_nav_correction_original_tx_identity
    ON unit_transactions (id, nav_id, contract_id, fund_id);
CREATE UNIQUE INDEX uq_nav_correction_tx_order_identity
    ON unit_transactions (id, order_id, contract_id);

CREATE TABLE nav_correction_journal (
    replacement_nav_id          UUID NOT NULL,
    original_transaction_id     UUID NOT NULL,
    original_nav_id             UUID NOT NULL,
    contract_id                 UUID NOT NULL,
    fund_id                     UUID NOT NULL,
    currency                    CHAR(3) NOT NULL,
    original_order_id           UUID REFERENCES unit_orders (id),
    linked_switch_order_id      UUID REFERENCES unit_orders (id),
    linked_switch_transaction_id UUID REFERENCES unit_transactions (id),
    transaction_type            VARCHAR(16) NOT NULL CHECK (transaction_type IN
        ('SUBSCRIBE', 'REDEEM', 'SWITCH_OUT', 'SWITCH_IN', 'FEE')),
    original_nav_per_unit       NUMERIC(19, 6) NOT NULL CHECK (original_nav_per_unit > 0),
    replacement_nav_per_unit    NUMERIC(19, 6) NOT NULL CHECK (replacement_nav_per_unit > 0),
    original_units              NUMERIC(25, 6) NOT NULL CHECK (original_units >= 0),
    proposed_units              NUMERIC(25, 6) NOT NULL CHECK (proposed_units >= 0),
    units_delta                 NUMERIC(25, 6) NOT NULL,
    original_amount             NUMERIC(21, 2) NOT NULL CHECK (original_amount >= 0),
    proposed_amount             NUMERIC(21, 2) NOT NULL CHECK (proposed_amount >= 0),
    cash_delta                  NUMERIC(21, 2) NOT NULL,
    rounding_residual           NUMERIC(37, 12) NOT NULL,
    reason                      VARCHAR(1024) NOT NULL CHECK (length(trim(reason)) > 0),
    rule_version                VARCHAR(64) NOT NULL CHECK (length(trim(rule_version)) > 0),
    maker                       VARCHAR(256) NOT NULL,
    checker                     VARCHAR(256) NOT NULL,
    effective_at                TIMESTAMPTZ NOT NULL,
    recorded_at                 TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (replacement_nav_id, original_transaction_id),
    UNIQUE (replacement_nav_id, original_transaction_id, contract_id, fund_id, currency),
    CONSTRAINT ck_nav_correction_four_eyes CHECK (
        length(trim(maker)) > 0 AND length(trim(checker)) > 0 AND trim(maker) <> trim(checker)
    ),
    CONSTRAINT ck_nav_correction_instruction_link CHECK (
        (transaction_type = 'FEE' AND original_order_id IS NULL) OR
        (transaction_type <> 'FEE' AND original_order_id IS NOT NULL)
    ),
    CONSTRAINT ck_nav_correction_switch_link CHECK (
        (transaction_type IN ('SWITCH_OUT', 'SWITCH_IN') AND
            linked_switch_order_id IS NOT NULL AND linked_switch_transaction_id IS NOT NULL) OR
        (transaction_type NOT IN ('SWITCH_OUT', 'SWITCH_IN') AND
            linked_switch_order_id IS NULL AND linked_switch_transaction_id IS NULL)
    ),
    CONSTRAINT ck_nav_correction_units_delta CHECK (
        (transaction_type = 'FEE' AND units_delta = original_units - proposed_units) OR
        (transaction_type IN ('SUBSCRIBE', 'SWITCH_IN') AND units_delta = proposed_units - original_units) OR
        (transaction_type IN ('REDEEM', 'SWITCH_OUT') AND units_delta = 0)
    ),
    CONSTRAINT ck_nav_correction_cash_delta CHECK (cash_delta = proposed_amount - original_amount),
    FOREIGN KEY (replacement_nav_id, original_nav_id, fund_id)
        REFERENCES fund_navs (id, corrects_nav_id, fund_id),
    FOREIGN KEY (original_transaction_id, original_nav_id, contract_id, fund_id)
        REFERENCES unit_transactions (id, nav_id, contract_id, fund_id),
    FOREIGN KEY (original_transaction_id, original_order_id, contract_id)
        REFERENCES unit_transactions (id, order_id, contract_id),
    FOREIGN KEY (linked_switch_transaction_id, linked_switch_order_id, contract_id)
        REFERENCES unit_transactions (id, order_id, contract_id)
);

CREATE TABLE nav_correction_obligation_drafts (
    replacement_nav_id       UUID NOT NULL,
    original_transaction_id  UUID NOT NULL,
    contract_id              UUID NOT NULL,
    fund_id                  UUID NOT NULL,
    currency                 CHAR(3) NOT NULL,
    kind                     VARCHAR(5) NOT NULL CHECK (kind IN ('CASH', 'UNITS')),
    signed_delta             NUMERIC(25, 6) NOT NULL CHECK (signed_delta <> 0),
    PRIMARY KEY (replacement_nav_id, original_transaction_id, kind),
    FOREIGN KEY (replacement_nav_id, original_transaction_id, contract_id, fund_id, currency)
        REFERENCES nav_correction_journal
            (replacement_nav_id, original_transaction_id, contract_id, fund_id, currency),
    CONSTRAINT ck_nav_correction_obligation_scale CHECK (
        kind = 'UNITS' OR signed_delta = round(signed_delta, 2)
    )
);

CREATE FUNCTION check_nav_correction_journal_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    source_row RECORD;
BEGIN
    SELECT tx.units, tx.amount, tx.nav_per_unit, tx.transaction_type, tx.order_id,
           original.nav_per_unit AS original_nav_price,
           replacement.nav_per_unit AS replacement_nav_price,
           fund.currency AS fund_currency
      INTO source_row
      FROM unit_transactions tx
      JOIN fund_navs original ON original.id = tx.nav_id
      JOIN fund_navs replacement ON replacement.id = NEW.replacement_nav_id
      JOIN funds fund ON fund.id = tx.fund_id
     WHERE tx.id = NEW.original_transaction_id
       AND original.id = NEW.original_nav_id
       AND replacement.corrects_nav_id = original.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'correction journal has no priced source';
    END IF;
    IF NEW.original_units <> source_row.units OR
       NEW.original_amount <> source_row.amount OR
       NEW.original_nav_per_unit <> source_row.nav_per_unit OR
       NEW.original_nav_per_unit <> source_row.original_nav_price OR
       NEW.replacement_nav_per_unit <> source_row.replacement_nav_price OR
       NEW.transaction_type <> source_row.transaction_type OR
       NEW.original_order_id IS DISTINCT FROM source_row.order_id OR
       NEW.currency <> source_row.fund_currency THEN
        RAISE EXCEPTION 'correction journal snapshot does not match priced source';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER nav_correction_journal_matches_source
    BEFORE INSERT ON nav_correction_journal
    FOR EACH ROW EXECUTE FUNCTION check_nav_correction_journal_snapshot();

CREATE FUNCTION check_nav_correction_draft_delta() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    expected NUMERIC;
BEGIN
    SELECT CASE WHEN NEW.kind = 'CASH' THEN cash_delta ELSE units_delta END INTO expected
    FROM nav_correction_journal
    WHERE replacement_nav_id = NEW.replacement_nav_id
      AND original_transaction_id = NEW.original_transaction_id;
    IF expected IS NULL OR NEW.signed_delta <> expected THEN
        RAISE EXCEPTION 'correction obligation draft does not match its journal line';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER nav_correction_draft_delta_matches_journal
    BEFORE INSERT ON nav_correction_obligation_drafts
    FOR EACH ROW EXECUTE FUNCTION check_nav_correction_draft_delta();

CREATE FUNCTION reject_nav_correction_draft_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'NAV correction calculated evidence is append-only';
END;
$$;

CREATE TRIGGER nav_correction_journal_append_only
    BEFORE UPDATE OR DELETE ON nav_correction_journal
    FOR EACH ROW EXECUTE FUNCTION reject_nav_correction_draft_mutation();
CREATE TRIGGER nav_correction_obligation_drafts_append_only
    BEFORE UPDATE OR DELETE ON nav_correction_obligation_drafts
    FOR EACH ROW EXECUTE FUNCTION reject_nav_correction_draft_mutation();

CREATE TRIGGER nav_correction_journal_no_truncate
    BEFORE TRUNCATE ON nav_correction_journal
    FOR EACH STATEMENT EXECUTE FUNCTION reject_nav_correction_draft_mutation();
CREATE TRIGGER nav_correction_obligation_drafts_no_truncate
    BEFORE TRUNCATE ON nav_correction_obligation_drafts
    FOR EACH STATEMENT EXECUTE FUNCTION reject_nav_correction_draft_mutation();

-- The original price/amount/units must remain observable after any later correction. The only
-- production writer creates transactions at forward settlement; no lifecycle transition updates
-- a priced transaction. This also prevents the old in-place repricing helper from being persisted.
CREATE TRIGGER unit_transactions_priced_history_append_only
    BEFORE UPDATE OR DELETE ON unit_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_nav_correction_draft_mutation();
CREATE TRIGGER unit_transactions_priced_history_no_truncate
    BEFORE TRUNCATE ON unit_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION reject_nav_correction_draft_mutation();
