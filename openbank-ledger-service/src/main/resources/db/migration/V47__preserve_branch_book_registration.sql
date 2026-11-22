-- B2. A registered book cannot be moved to a different bank/country after journals use it.
-- Rollback: stop branch writers; retain the register and this guard for audit/reconciliation.
CREATE FUNCTION preserve_branch_book_registration() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.establishment_id,NEW.bank_id,NEW.host_country)
       IS DISTINCT FROM (OLD.establishment_id,OLD.bank_id,OLD.host_country) THEN
        RAISE EXCEPTION 'branch book registration is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_preserve_branch_book_registration BEFORE UPDATE ON ledger_branch_books
    FOR EACH ROW EXECUTE FUNCTION preserve_branch_book_registration();
