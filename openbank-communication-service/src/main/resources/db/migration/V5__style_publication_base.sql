-- Existing pending drafts have no recorded base. Mark them stale so a checker must
-- recreate/review them against the current publication; migration cannot infer their base.
-- Rollback: stop publication, then DROP COLUMN base_published_version. Drafts created after
-- the rollback must be reviewed again before restoring the precondition.
ALTER TABLE style_version ADD COLUMN base_published_version integer NOT NULL DEFAULT 0;
UPDATE style_version SET base_published_version = -1 WHERE status IN ('DRAFT', 'IN_REVIEW');
ALTER TABLE style_version ADD CONSTRAINT style_version_base_published_or_legacy
    CHECK (base_published_version >= -1);
