-- Reader-first B2. Historical attribution remains unknown; never infer it from a movement.
-- Rollback: stop consumers and keep context, dated dedup and reconciliation evidence.
CREATE TABLE account_booking_context (
    account_id UUID PRIMARY KEY,
    bank_id UUID,
    establishment_id UUID,
    host_country TEXT,
    mode TEXT,
    synthetic BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((bank_id IS NULL AND establishment_id IS NULL AND host_country IS NULL AND mode IS NULL)
       OR (bank_id IS NOT NULL AND establishment_id IS NOT NULL AND host_country IS NOT NULL AND mode IS NOT NULL AND host_country ~ '^[A-Z]{2}$'
           AND host_country <> 'CZ' AND mode IN ('FOS','BRANCH')))
);
CREATE FUNCTION preserve_balance_booking_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'account booking context is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_preserve_balance_booking_context BEFORE UPDATE OR DELETE ON account_booking_context
    FOR EACH ROW EXECUTE FUNCTION preserve_balance_booking_context();
ALTER TABLE ledger_projection_event ADD COLUMN branch_book_id UUID;
CREATE TABLE branch_book_reconciliations (
    id BIGSERIAL PRIMARY KEY,
    bank_id UUID NOT NULL,
    as_of DATE NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    operation_key TEXT UNIQUE CHECK (operation_key IS NULL OR char_length(btrim(operation_key)) BETWEEN 1 AND 255),
    report JSONB NOT NULL
);
CREATE INDEX idx_branch_book_reconciliation_latest ON branch_book_reconciliations(bank_id,id DESC);
