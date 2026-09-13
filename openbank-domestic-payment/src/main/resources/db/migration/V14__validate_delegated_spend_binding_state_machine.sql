-- Validate V13's already-enforced constraints in a separate Flyway transaction. This preserves
-- the expand-first lock boundary: V13 creates the table and commits before any validation scan.
--
-- Rollback: no action. Validation changes no accepted data shape; V13 owns constraint removal.

ALTER TABLE domestic_delegated_spend_bindings
    VALIDATE CONSTRAINT chk_domestic_delegated_spend_contract;

ALTER TABLE domestic_delegated_spend_bindings
    VALIDATE CONSTRAINT chk_domestic_delegated_spend_revision;

ALTER TABLE domestic_delegated_spend_bindings
    VALIDATE CONSTRAINT chk_domestic_delegated_spend_binding_state;

ALTER TABLE domestic_delegated_spend_bindings
    VALIDATE CONSTRAINT fk_domestic_delegated_spend_payment;

ALTER TABLE domestic_payments
    VALIDATE CONSTRAINT fk_domestic_payment_delegated_spend_binding;

ALTER TABLE domestic_payments
    VALIDATE CONSTRAINT chk_domestic_payments_delegation_binding;
