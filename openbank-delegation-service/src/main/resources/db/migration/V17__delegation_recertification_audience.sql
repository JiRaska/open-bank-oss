-- Review policy context for a grant. It never participates in authorisation; it records the
-- grantor-selected cadence for a future periodic-review workflow.
--
-- COMPANY is intentionally not auto-classified as SME or CORPORATE: party-service has no safe
-- size attribute. Existing grants remain NULL and are not guessed/backfilled.
--
-- Rollback: deploy older images first (they ignore this nullable column). If removal is ever
-- required, use a new forward migration; never edit an applied Flyway migration.
ALTER TABLE delegation_grants
    ADD COLUMN recertification_audience VARCHAR(16);

ALTER TABLE delegation_grants
    ADD CONSTRAINT chk_delegation_recertification_audience
    CHECK (recertification_audience IS NULL OR recertification_audience IN ('PERSONAL', 'FOP', 'SME', 'CORPORATE'));
