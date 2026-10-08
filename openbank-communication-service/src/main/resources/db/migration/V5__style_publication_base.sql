-- Existing pending drafts inherit the currently published version at migration time.
-- Rollback: stop publication, then DROP COLUMN base_published_version. Drafts created after
-- the rollback must be reviewed again before restoring the precondition.
ALTER TABLE style_version ADD COLUMN base_published_version integer NOT NULL DEFAULT 0;
UPDATE style_version draft SET base_published_version = COALESCE(
    (SELECT current_style.version FROM style_version current_style
     WHERE current_style.persona_id = draft.persona_id AND current_style.status = 'PUBLISHED'),
    0
);
ALTER TABLE style_version ADD CONSTRAINT style_version_base_published_nonnegative
    CHECK (base_published_version >= 0);
