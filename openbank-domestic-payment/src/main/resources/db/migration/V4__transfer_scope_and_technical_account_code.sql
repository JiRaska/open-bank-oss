ALTER TABLE domestic_payments
    ADD COLUMN IF NOT EXISTS transfer_scope VARCHAR(32) NOT NULL DEFAULT 'INTERNAL_CLIENT',
    ADD COLUMN IF NOT EXISTS technical_account_code VARCHAR(64);

UPDATE domestic_payments
SET transfer_scope = COALESCE(transfer_scope, 'INTERNAL_CLIENT')
WHERE transfer_scope IS NULL;

ALTER TABLE domestic_payments
    ALTER COLUMN transfer_scope SET DEFAULT 'INTERNAL_CLIENT',
    ALTER COLUMN transfer_scope SET NOT NULL;

COMMENT ON COLUMN domestic_payments.transfer_scope IS 'Domestic transfer scope used for routing and validation';
COMMENT ON COLUMN domestic_payments.technical_account_code IS 'Technical account code required for TECHNICAL_ACCOUNT transfers';
