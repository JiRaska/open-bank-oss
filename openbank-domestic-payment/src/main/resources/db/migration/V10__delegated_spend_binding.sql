-- Additive, nullable context for binding a delegated spend reservation to exactly one domestic
-- payment. Existing owner-initiated rows remain valid with all three columns NULL.
--
-- Rollback (code must stop writing/reading the columns first):
--   ALTER TABLE domestic_payments DROP CONSTRAINT uq_domestic_payments_reservation_id;
--   ALTER TABLE domestic_payments DROP CONSTRAINT chk_domestic_payments_delegation_binding;
--   ALTER TABLE domestic_payments DROP COLUMN reservation_id, DROP COLUMN delegation_id,
--       DROP COLUMN initiated_by_party_id;

ALTER TABLE domestic_payments
    ADD COLUMN initiated_by_party_id UUID,
    ADD COLUMN delegation_id UUID,
    ADD COLUMN reservation_id UUID;

ALTER TABLE domestic_payments
    ADD CONSTRAINT chk_domestic_payments_delegation_binding
        CHECK ((delegation_id IS NULL) = (reservation_id IS NULL)) NOT VALID,
    ADD CONSTRAINT uq_domestic_payments_reservation_id UNIQUE (reservation_id);
