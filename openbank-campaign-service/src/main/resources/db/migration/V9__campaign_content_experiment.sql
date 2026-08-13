-- A/B assignment is written once at enrolment so retries and audit reads never recalculate which
-- wording a party saw. Nullable preserves all historic campaigns and deliberate no-contact holds.
ALTER TABLE enrolments
    ADD COLUMN content_variant TEXT
    CHECK (content_variant IN ('A', 'B'));

-- Rollback: retain this additive nullable column until every deployed reader understands it. A
-- destructive DROP is only safe in a later migration after all content-experiment campaigns end.
