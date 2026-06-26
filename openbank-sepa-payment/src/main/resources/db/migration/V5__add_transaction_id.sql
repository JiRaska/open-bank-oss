-- Link each SEPA payment to the transaction-service transaction that booked its debit.
-- Nullable: payments created before ADR-0108/ADR-0109 have no linked transaction.
ALTER TABLE sepa_payments ADD COLUMN IF NOT EXISTS transaction_id UUID;
