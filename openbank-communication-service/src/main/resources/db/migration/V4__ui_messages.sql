-- Approved mobile copy participates in the existing immutable style-version lifecycle.
-- Rollback: After stopping style publication, remove the ui_messages column if no published
-- style depends on it; otherwise restore the prior style_version data from backup first.
ALTER TABLE style_version ADD COLUMN ui_messages TEXT NOT NULL DEFAULT '{}';
