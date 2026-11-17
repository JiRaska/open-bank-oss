-- Reader-first B2 context. Historical contexts remain NULL: no fabricated backfill.
-- Rollback: stop new passport writers; retain context columns and outbox evidence for reconciliation.
ALTER TABLE accounts
    ADD COLUMN booking_bank_id UUID,
    ADD COLUMN booking_establishment_id UUID,
    ADD COLUMN booking_host_country VARCHAR(2),
    ADD COLUMN booking_mode TEXT,
    ADD CONSTRAINT chk_account_booking_context_whole CHECK (
        num_nonnulls(booking_bank_id,booking_establishment_id,booking_host_country,booking_mode) IN (0,4)
        AND (booking_mode IS NULL OR booking_mode IN ('FOS','BRANCH'))
        AND (booking_host_country IS NULL OR (booking_host_country ~ '^[A-Z]{2}$' AND booking_host_country <> 'CZ'))
    );
CREATE FUNCTION preserve_account_booking_context() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.booking_bank_id,NEW.booking_establishment_id,NEW.booking_host_country,NEW.booking_mode)
        IS DISTINCT FROM (OLD.booking_bank_id,OLD.booking_establishment_id,OLD.booking_host_country,OLD.booking_mode) THEN
        RAISE EXCEPTION 'account booking context is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_preserve_account_booking_context BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION preserve_account_booking_context();
