-- SPDX-License-Identifier: Apache-2.0
-- Rollback: stop new writers; retain immutable routing and outbox evidence. Do not fabricate legacy attribution.
ALTER TABLE transactions ADD COLUMN source_booking_bank_id UUID;
ALTER TABLE transactions ADD COLUMN source_booking_establishment_id UUID;
ALTER TABLE transactions ADD COLUMN source_booking_host_country VARCHAR(2);
ALTER TABLE transactions ADD COLUMN source_booking_mode VARCHAR(6);
ALTER TABLE transactions ADD COLUMN target_booking_bank_id UUID;
ALTER TABLE transactions ADD COLUMN target_booking_establishment_id UUID;
ALTER TABLE transactions ADD COLUMN target_booking_host_country VARCHAR(2);
ALTER TABLE transactions ADD COLUMN target_booking_mode VARCHAR(6);
CREATE FUNCTION keep_transaction_booking_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.source_booking_bank_id,NEW.source_booking_establishment_id,NEW.source_booking_host_country,NEW.source_booking_mode,
           NEW.target_booking_bank_id,NEW.target_booking_establishment_id,NEW.target_booking_host_country,NEW.target_booking_mode)
       IS DISTINCT FROM ROW(OLD.source_booking_bank_id,OLD.source_booking_establishment_id,OLD.source_booking_host_country,OLD.source_booking_mode,
           OLD.target_booking_bank_id,OLD.target_booking_establishment_id,OLD.target_booking_host_country,OLD.target_booking_mode)
       OR ((OLD.source_booking_bank_id IS NOT NULL OR OLD.target_booking_bank_id IS NOT NULL)
           AND ROW(NEW.source_account_id,NEW.target_account_id)
               IS DISTINCT FROM ROW(OLD.source_account_id,OLD.target_account_id)) THEN
        RAISE EXCEPTION 'Transaction booking context is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER transactions_keep_booking_context BEFORE UPDATE ON transactions
FOR EACH ROW EXECUTE FUNCTION keep_transaction_booking_context();
ALTER TABLE transactions ADD CONSTRAINT source_booking_tuple CHECK (
    num_nonnulls(source_booking_bank_id,source_booking_establishment_id,source_booking_host_country,source_booking_mode) IN (0,4)
    AND (source_booking_mode IS NULL OR (source_booking_mode IN ('FOS','BRANCH') AND source_account_id IS NOT NULL
        AND source_booking_host_country ~ '^[A-Z]{2}$' AND source_booking_host_country <> 'CZ')));
ALTER TABLE transactions ADD CONSTRAINT target_booking_tuple CHECK (
    num_nonnulls(target_booking_bank_id,target_booking_establishment_id,target_booking_host_country,target_booking_mode) IN (0,4)
    AND (target_booking_mode IS NULL OR (target_booking_mode IN ('FOS','BRANCH') AND target_account_id IS NOT NULL
        AND target_booking_host_country ~ '^[A-Z]{2}$' AND target_booking_host_country <> 'CZ')));

CREATE INDEX transactions_pending_branch_dispatch ON transactions(initiated_at)
WHERE status='PENDING' AND (source_booking_mode='BRANCH' OR target_booking_mode='BRANCH');
