-- Approved mobile copy participates in the existing immutable style-version lifecycle.
ALTER TABLE style_version ADD COLUMN ui_messages TEXT NOT NULL DEFAULT '{}';
