-- ADR-0334 #12378: real payment adapters.
--
-- 1. pension_payment_mandates: the standing order / SEPA direct-debit mandate pension-service set
--    up downstream for a contract, so a cancellation can be checked against the contract that owns
--    it (a mandate id alone is never trusted from a caller). The pension-side id is assigned by
--    the application; (kind, external_id) is unique, so a retried set-up records one row.
-- 2. pension_payment_instructions.status gains SETTLED and REJECTED: domestic-payment's own
--    status changes (openbank.domestic.payment.events) are written back onto the instruction
--    the payout workflow sent, so a payout that was rejected or returned is visible as such
--    instead of reading SENT forever.
--
-- Rollback:
--   DROP TABLE pension_payment_mandates;
--   UPDATE pension_payment_instructions SET status = 'SENT' WHERE status IN ('SETTLED', 'REJECTED');
--   ALTER TABLE pension_payment_instructions DROP CONSTRAINT pension_payment_instructions_status_known;
--   ALTER TABLE pension_payment_instructions
--       ADD CONSTRAINT pension_payment_instructions_status_check CHECK (status IN ('PENDING', 'SENT'));
--   DROP INDEX idx_pension_payment_instructions_payment_ref;
-- The UPDATE loses only the settlement outcome; the instruction and its payment_ref survive.

-- The V4 inline CHECK carries Postgres's generated name. No IF EXISTS: if the name ever differs,
-- the migration must fail loudly rather than leave the old two-value CHECK in place.
ALTER TABLE pension_payment_instructions DROP CONSTRAINT pension_payment_instructions_status_check;
ALTER TABLE pension_payment_instructions
    ADD CONSTRAINT pension_payment_instructions_status_known
        CHECK (status IN ('PENDING', 'SENT', 'SETTLED', 'REJECTED'));
CREATE INDEX idx_pension_payment_instructions_payment_ref ON pension_payment_instructions (payment_ref);

CREATE TABLE pension_payment_mandates (
    id           UUID PRIMARY KEY,
    contract_id  UUID         NOT NULL REFERENCES pension_contracts (contract_id),
    kind         VARCHAR(16)  NOT NULL CHECK (kind IN ('STANDING_ORDER', 'DIRECT_DEBIT')),
    external_id  VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pension_payment_mandates_external_unique UNIQUE (kind, external_id)
);
CREATE INDEX idx_pension_payment_mandates_contract ON pension_payment_mandates (contract_id);
