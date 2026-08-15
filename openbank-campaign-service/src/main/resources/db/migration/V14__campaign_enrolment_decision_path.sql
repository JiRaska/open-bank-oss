-- Optional durable audit trail for selected graph edges.  A NULL keeps pre-graph enrolments
-- semantically unchanged and lets a rollback ignore the additive column safely.
ALTER TABLE enrolments ADD COLUMN decision_path_json text;
