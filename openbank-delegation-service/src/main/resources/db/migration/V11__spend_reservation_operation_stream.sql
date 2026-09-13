-- Dedicated, compacted delegated-spend reservation state stream.
--
-- This migration intentionally follows the delegation lifecycle revision migrations V8/V9/V10.
-- Existing rows and callers are rail-neutral. Only an explicitly classified DOMESTIC_PAYMENT
-- reservation enters the state stream. NOT VALID constraints enforce new writes without scanning
-- the live table in this transaction; V12 validates historical rows separately.
--
-- Rollback: this is not a simple writer-off rollback. Stop new domestic creators, settle or
-- durably reseed every existing reservation, and prove both queries return zero before contraction:
--   SELECT COUNT(*) FROM delegation_spend_reservations
--    WHERE operation_type = 'DOMESTIC_PAYMENT' AND state = 'RESERVED';
--   SELECT COUNT(*) FROM delegation_outbox
--    WHERE event_type = 'DelegationSpendReservationStateChanged' AND status <> 'SENT';
-- Then drop idx_delegation_outbox_spend_hol and the three additions below.

ALTER TABLE delegation_spend_reservations
    ADD COLUMN operation_type VARCHAR(40) NOT NULL DEFAULT 'UNSPECIFIED';

ALTER TABLE delegation_spend_reservations
    ADD CONSTRAINT chk_delegation_spend_operation_type
    CHECK (operation_type IN ('UNSPECIFIED', 'DOMESTIC_PAYMENT')) NOT VALID;

ALTER TABLE delegation_spend_reservations
    ADD CONSTRAINT chk_delegation_spend_domestic_key_length
    CHECK (operation_type <> 'DOMESTIC_PAYMENT' OR CHAR_LENGTH(idempotency_key) <= 128) NOT VALID;

CREATE INDEX idx_delegation_outbox_spend_hol
    ON delegation_outbox (
        aggregate_id,
        (((payload::jsonb ->> 'reservationVersion')::BIGINT))
    )
    WHERE event_type = 'DelegationSpendReservationStateChanged' AND status <> 'SENT';
