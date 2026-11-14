-- Bind new customer-edge payments to the authenticated customer and debit authority, atomically
-- with the original payment. Older rows lack this attribution and cannot be claimed by a lookup.
-- Rollback: deploy the previous code first. Leave this nullable column in place to preserve
-- attribution for a roll-forward. Dropping it would erase that evidence and is unnecessary.
ALTER TABLE domestic_payments ADD COLUMN customer_submission_scope VARCHAR(64);
