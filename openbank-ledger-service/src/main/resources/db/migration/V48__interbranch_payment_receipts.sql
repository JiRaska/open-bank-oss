-- SPDX-License-Identifier: Apache-2.0
-- ADR-0380 B2.
-- Rollback: stop branch payment writers; retain receipts, journals and clearing accounts.
CREATE TABLE ledger_branch_payments (
    operation_key VARCHAR(255) PRIMARY KEY,
    request_digest VARCHAR(64) NOT NULL,
    bank_id UUID NOT NULL,
    first_journal_id UUID,
    second_journal_id UUID,
    CHECK (first_journal_id IS NOT NULL OR second_journal_id IS NULL)
);

INSERT INTO gl_accounts (id,code,name,type,currency_code,is_leaf,is_enabled) VALUES
('a0000000-0000-0000-0000-000000001290','1290','Inter-branch clearing CZK','ASSET','CZK',true,true),
('a0000000-0000-0000-0000-000000001291','1291','Inter-branch clearing EUR','ASSET','EUR',true,true),
('a0000000-0000-0000-0000-000000001292','1292','Inter-branch clearing USD','ASSET','USD',true,true),
('a0000000-0000-0000-0000-000000001293','1293','Inter-branch clearing GBP','ASSET','GBP',true,true);

CREATE FUNCTION keep_branch_payment_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Branch payment receipt cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF (NEW.operation_key,NEW.request_digest,NEW.bank_id) IS DISTINCT FROM
       (OLD.operation_key,OLD.request_digest,OLD.bank_id) OR OLD.first_journal_id IS NOT NULL THEN
        RAISE EXCEPTION 'Branch payment receipt is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER ledger_branch_payments_keep_receipt BEFORE UPDATE OR DELETE ON ledger_branch_payments
FOR EACH ROW EXECUTE FUNCTION keep_branch_payment_receipt();
