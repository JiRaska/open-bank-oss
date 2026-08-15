-- Widen `channel` to fit a namespace prefix (issue #4660).
--
-- V9 sized it VARCHAR(16) for ADR-0226's own AuditChannel values ("ui"/"mcp"/"api"). Since then,
-- two OTHER producers turned out to write into the same JSON key: OnboardingChannel
-- (party-service, values up to "MOBILE_APP") and ComplaintChannel (dispute-service, values up to
-- "ARBITER") — a fleet sweep found AuditConsumer copies whatever top-level "channel" key any
-- subscribed topic happens to carry, verbatim, with no way to tell which vocabulary a stored value
-- belongs to. The fix (this migration's companion code change) prefixes the value by its source
-- topic — "onboarding:MOBILE_APP" is 22 characters, already past the old limit before the fix even
-- lands. Widening first, separately, so the column can hold the new shape before anything writes it.
--
-- ALTER COLUMN ... TYPE on a VARCHAR is a metadata-only change in Postgres when only widening (no
-- table rewrite, no lock beyond the brief one the DDL itself takes) — safe on a live table.
ALTER TABLE audit_entries
    ALTER COLUMN channel TYPE VARCHAR(32);
