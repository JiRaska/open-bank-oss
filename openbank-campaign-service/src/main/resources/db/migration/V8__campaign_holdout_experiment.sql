-- ADR-0245: a durable control assignment makes campaign impact measurable rather than inferred
-- from last-touch attribution alone. Existing campaigns/enrolments remain treatment, preserving
-- their pre-experiment behaviour exactly.
ALTER TABLE campaigns
    ADD COLUMN holdout_percent INTEGER NOT NULL DEFAULT 0
    CHECK (holdout_percent BETWEEN 0 AND 50);

ALTER TABLE enrolments
    ADD COLUMN experiment_cohort TEXT NOT NULL DEFAULT 'TREATMENT'
    CHECK (experiment_cohort IN ('TREATMENT', 'HOLDOUT'));

-- Rollback: retain these additive columns until every deployed reader understands them. They are
-- harmless at the zero/TREATMENT defaults; a destructive DROP is only safe in a later migration.
