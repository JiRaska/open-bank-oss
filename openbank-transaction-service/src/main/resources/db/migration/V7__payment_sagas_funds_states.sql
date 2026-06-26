-- V7: Align chk_payment_sagas_state with the full PaymentSaga state machine.
--
-- The orchestrator persists two in-flight states the original V5 CHECK omitted:
-- FUNDS_RESERVED (after the reservation leg) and FUNDS_CAPTURED (after capture).
-- A saga that progressed past reservation therefore violated the constraint
-- (SQLSTATE 23514) and failed mid-flight — a money-path defect that only stayed
-- hidden while the upstream ledger call short-circuited the saga earlier.
--
-- V5 is already released, so it is left immutable; this migration drops and
-- recreates the constraint with the complete enum (domain/saga/PaymentSaga.kt).
--
-- Rollback: to revert, drop chk_payment_sagas_state and recreate it with the
-- original 7-state set (STARTED, PAYMENT_INITIATED, LEDGER_POSTING, COMPLETED,
-- COMPENSATING, COMPENSATED, FAILED). Only safe if no row holds FUNDS_RESERVED
-- or FUNDS_CAPTURED at that moment.

ALTER TABLE payment_sagas DROP CONSTRAINT chk_payment_sagas_state;

ALTER TABLE payment_sagas ADD CONSTRAINT chk_payment_sagas_state CHECK (
    state IN (
        'STARTED',
        'PAYMENT_INITIATED',
        'FUNDS_RESERVED',
        'LEDGER_POSTING',
        'FUNDS_CAPTURED',
        'COMPLETED',
        'COMPENSATING',
        'COMPENSATED',
        'FAILED'
    )
);
