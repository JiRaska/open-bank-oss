-- Durable local projection and race arbiter for delegated domestic-payment reservations.
--
-- Expand-first only: neither the Kafka consumer nor the finalizer is enabled by this migration.
-- Rows are retained permanently after FINALIZED_ABSENT so a delayed RESERVED snapshot can never
-- reopen a reservation that this service has already proved will not be bound to a payment.
--
-- Checks and FKs are installed NOT VALID and validated in V14. PostgreSQL still enforces them for
-- every new row immediately, while V14 avoids holding this migration's CREATE/ALTER locks through
-- a validation scan once the table contains data.
--
-- Rollback (disable the consumer, finalizer and delegated-create seam first, then verify no
-- PENDING rows and retain/export BOUND and FINALIZED_ABSENT evidence before any destructive step):
--   ALTER TABLE domestic_payments DROP CONSTRAINT fk_domestic_payment_delegated_spend_binding;
--   DROP TABLE domestic_delegated_spend_bindings;

CREATE TABLE domestic_delegated_spend_bindings (
    reservation_id UUID PRIMARY KEY,
    delegation_id UUID NOT NULL,
    grantor_party_id UUID NOT NULL,
    grantee_party_id UUID NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id UUID NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    amount NUMERIC(20, 6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    -- Domain-separated SHA-256 only. The indefinitely retained compacted projection must never
    -- persist the caller's raw Idempotency-Key.
    idempotency_key_hash CHAR(64) NOT NULL,
    reservation_state VARCHAR(16) NOT NULL,
    reservation_version BIGINT NOT NULL,
    schema_version BIGINT NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    source_created_at TIMESTAMPTZ NOT NULL,
    source_settled_at TIMESTAMPTZ,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    last_event_id UUID NOT NULL,
    binding_state VARCHAR(32) NOT NULL,
    payment_id UUID,
    observed_at TIMESTAMPTZ NOT NULL,
    bound_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Non-partial target for the inverse composite FK below. A PENDING binding has NULL payment_id
    -- and therefore cannot authorize a delegated payment row; only the exact BOUND pair can.
    CONSTRAINT uq_domestic_delegated_spend_reservation_payment
        UNIQUE (reservation_id, payment_id)
);

ALTER TABLE domestic_delegated_spend_bindings
    ADD CONSTRAINT chk_domestic_delegated_spend_contract
        CHECK (
            schema_version = 1
            AND upper(aggregate_type) = 'DELEGATIONSPENDRESERVATION'
            AND source_service = 'delegation-service'
            AND resource_type = 'ACCOUNT'
            AND operation_type = 'DOMESTIC_PAYMENT'
            AND amount > 0
            AND currency ~ '^[A-Z]{3}$'
            AND idempotency_key_hash ~ '^[0-9a-f]{64}$'
        ) NOT VALID,
    ADD CONSTRAINT chk_domestic_delegated_spend_revision
        CHECK (
            (
                reservation_state = 'RESERVED'
                AND reservation_version = 1
                AND source_settled_at IS NULL
            )
            OR
            (
                reservation_state IN ('CONFIRMED', 'RELEASED')
                AND reservation_version = 2
                AND source_settled_at IS NOT NULL
            )
        ) NOT VALID,
    ADD CONSTRAINT chk_domestic_delegated_spend_binding_state
        CHECK (
            (
                binding_state = 'PENDING'
                AND reservation_state = 'RESERVED'
                AND reservation_version = 1
                AND payment_id IS NULL
                AND bound_at IS NULL
                AND finalized_at IS NULL
            )
            OR
            (
                binding_state = 'BOUND'
                AND payment_id IS NOT NULL
                AND bound_at IS NOT NULL
                AND finalized_at IS NULL
            )
            OR
            (
                binding_state = 'FINALIZED_ABSENT'
                AND payment_id IS NULL
                AND bound_at IS NULL
                AND finalized_at IS NOT NULL
            )
        ) NOT VALID,
    ADD CONSTRAINT fk_domestic_delegated_spend_payment
        FOREIGN KEY (payment_id) REFERENCES domestic_payments(payment_id) ON DELETE NO ACTION
        DEFERRABLE INITIALLY DEFERRED NOT VALID;

-- The inverse deferred composite FK closes the bypass hole: no future persistence path can insert
-- a delegated payment row against a merely PENDING binding. Only the exact (reservation,payment)
-- pair installed by PENDING -> BOUND satisfies it. Both directions are deferred so Hibernate flush
-- order is irrelevant while payment + outbox + PENDING -> BOUND stay atomic. MATCH SIMPLE leaves
-- owner-funded rows with a NULL reservation outside this relationship.
ALTER TABLE domestic_payments
    ADD CONSTRAINT fk_domestic_payment_delegated_spend_binding
        FOREIGN KEY (reservation_id, payment_id)
        REFERENCES domestic_delegated_spend_bindings(reservation_id, payment_id)
        MATCH SIMPLE ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED NOT VALID;

CREATE UNIQUE INDEX uq_domestic_delegated_spend_payment
    ON domestic_delegated_spend_bindings(payment_id)
    WHERE payment_id IS NOT NULL;

CREATE INDEX idx_domestic_delegated_spend_pending
    ON domestic_delegated_spend_bindings(observed_at, reservation_id)
    WHERE binding_state = 'PENDING';
