-- Explicit journey decisions are optional.  NULL preserves all historic linear definitions and
-- provides a direct rollback: an older service ignores this additive column without losing steps.
ALTER TABLE campaigns ADD COLUMN decisions_json text;
