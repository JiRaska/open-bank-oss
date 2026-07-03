-- OpenBank Account Service — V13: optional savings goal on an account (ADR-0153).
-- All three nullable: a goal is "set" iff goal_target_minor_units IS NOT NULL. Currency
-- is implicitly the account's own currency (no cross-currency goal, ADR-0153 decision).
-- Rollback: DROP COLUMN goal_name, goal_target_minor_units, goal_target_date.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS goal_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS goal_target_minor_units BIGINT,
    ADD COLUMN IF NOT EXISTS goal_target_date DATE;
