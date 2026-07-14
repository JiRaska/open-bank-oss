-- Version-resolution policy (ADR-0162): a template `code` has at most one PUBLISHED version at
-- any time -- publishing a version supersedes and retires its predecessor. Rollback: DROP INDEX
-- uq_document_templates_one_published_per_code; the RETIRED rows this migration produces cannot
-- be un-retired automatically (their original status wasn't recorded) -- restore individually
-- from a backup if ever needed.

-- One-time cleanup: before this policy was enforced in application code (PR #1083's letterhead
-- update added new 1.1.0 seed rows without retiring their 1.0.0 predecessors), a `code` could
-- accumulate more than one PUBLISHED row. Retire every PUBLISHED row that has a newer PUBLISHED
-- row for the same code, keeping only the most recent as current. Generic (not id-hardcoded) so
-- it also cleans up any other pre-existing duplicate, not just the known VOP/framework/account set.
UPDATE document_templates t
SET status = 'RETIRED'
WHERE t.status = 'PUBLISHED'
  AND EXISTS (
      SELECT 1
      FROM document_templates newer
      WHERE newer.code = t.code
        AND newer.status = 'PUBLISHED'
        AND (newer.created_at, newer.id) > (t.created_at, t.id)
  );

-- Enforce the invariant going forward: a concurrent publish of the same code now fails fast with a
-- unique-violation (translated to TemplatePublishConflictException at the persistence adapter)
-- instead of silently leaving two "current" versions.
CREATE UNIQUE INDEX uq_document_templates_one_published_per_code
    ON document_templates (code)
    WHERE status = 'PUBLISHED';
