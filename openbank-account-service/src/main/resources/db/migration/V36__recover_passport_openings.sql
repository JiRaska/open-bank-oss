-- Persist the minimal, already screened opening before calling catalogue. No holder name
-- or raw screening evidence is copied to the recovery queue. Existing V35 rows are re-staged
-- only by a matching caller retry because their original request payload is unavailable.
-- Rollback: stop the recovery scheduler and drain the queue before dropping the trigger,
-- its function, and the recovery columns. Keep the original passport intent and receipt binding.
ALTER TABLE account_passport_intents
    ADD COLUMN opening_completed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN opening_draft JSONB,
    ADD COLUMN next_opening_attempt_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_passport_opening_recovery
        CHECK ((opening_draft IS NULL AND next_opening_attempt_at IS NULL) OR
               (operation = 'OPEN' AND NOT opening_completed AND opening_draft IS NOT NULL
                   AND next_opening_attempt_at IS NOT NULL));

UPDATE account_passport_intents i SET opening_completed = TRUE
WHERE i.operation = 'OPEN' AND EXISTS (SELECT 1 FROM accounts a WHERE a.id = i.account_id);

CREATE INDEX idx_passport_openings_due ON account_passport_intents (next_opening_attempt_at, operation_id)
    WHERE opening_draft IS NOT NULL;

CREATE FUNCTION clear_completed_passport_opening() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE account_passport_intents
       SET opening_completed = TRUE, opening_draft = NULL, next_opening_attempt_at = NULL
     WHERE account_id = NEW.id AND operation = 'OPEN' AND NOT opening_completed;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_clear_completed_passport_opening
    AFTER INSERT ON accounts FOR EACH ROW EXECUTE FUNCTION clear_completed_passport_opening();
