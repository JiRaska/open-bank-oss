-- A service-account JWT identifies the edge, not its customer. Bind the verified edge's party header
-- separately from the existing create fingerprint. Historical rows stay unverifiable for receipts.
-- Rollback after reverting the reader/writer:
--   ALTER TABLE domestic_payments DROP COLUMN receipt_customer_party_id;
--   ALTER TABLE domestic_payments DROP COLUMN receipt_customer_actor_id;
ALTER TABLE domestic_payments ADD COLUMN receipt_customer_party_id UUID;
ALTER TABLE domestic_payments ADD COLUMN receipt_customer_actor_id UUID;
