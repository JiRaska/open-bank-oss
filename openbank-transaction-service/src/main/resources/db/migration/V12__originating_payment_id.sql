-- ADR-0108: store the rail payment that triggered this transaction for reconciliation.
-- Nullable: existing transactions and operator-initiated payments have no originating rail payment.
ALTER TABLE transactions ADD COLUMN originating_payment_id UUID;
-- Rollback: ALTER TABLE transactions DROP COLUMN originating_payment_id;
