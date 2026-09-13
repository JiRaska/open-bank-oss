-- Validate V8's expand constraint after V8 committed and released ACCESS EXCLUSIVE.
-- PostgreSQL performs this scan under SHARE UPDATE EXCLUSIVE, allowing normal reads and writes.
ALTER TABLE delegation_grants
    VALIDATE CONSTRAINT chk_delegation_lifecycle_revision_nonnegative;

-- Rollback: validation adds no new data shape. Keep the validated constraint while either the
-- revision-aware producer or consumers remain deployed; V8 documents the eventual contract step.
