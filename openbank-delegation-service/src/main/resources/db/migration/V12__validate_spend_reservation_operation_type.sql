-- Validate the V11 constraints in a separate Flyway transaction.
-- Rollback: validation changes no data. Returning to NOT VALID requires dropping and re-adding the
-- constraints and is an operational compatibility action, not a routine application rollback.

ALTER TABLE delegation_spend_reservations
    VALIDATE CONSTRAINT chk_delegation_spend_operation_type;

ALTER TABLE delegation_spend_reservations
    VALIDATE CONSTRAINT chk_delegation_spend_domestic_key_length;
