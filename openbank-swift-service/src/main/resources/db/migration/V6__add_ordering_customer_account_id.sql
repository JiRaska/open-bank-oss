-- ADR-0108: store the internal account UUID alongside the IBAN so settlement can book funds.
-- Nullable: SWIFT messages submitted before this migration (and non-MT103 types) have no UUID.
ALTER TABLE swift_messages ADD COLUMN ordering_customer_account_id UUID NULL;
