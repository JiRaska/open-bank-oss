-- D3: per-category spending rules for a card — a monthly cap, a block, or both.
--
-- Separate table rather than columns on `cards` because the category taxonomy is expected to grow:
-- adding a category must not be a schema change, and a customer sets rules for a handful of
-- categories at most, so the table stays small and sparse.
--
-- Enforcement is in CardAuthorizationPolicy, in the authorisation path. Storing a rule the
-- authorisation path did not read is the failure this whole change exists to end: before it, the
-- channel toggles on `cards` were written, returned by the API, and consulted by nothing.
CREATE TABLE card_category_rules (
    card_id                   UUID        NOT NULL,
    -- Taxonomy id (GAMBLING, GROCERIES, …). Not an enum type: the taxonomy is served by the API
    -- and grows without a migration, and a Postgres enum would make each addition a schema change
    -- in lock-step with a deploy.
    category                  VARCHAR(40) NOT NULL,
    blocked                   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- NULL = no cap for this category. Zero is a real value meaning "nothing at all", which is
    -- distinct from "uncapped" — hence nullable rather than a 0 default.
    monthly_limit_minor_units BIGINT,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (card_id, category),
    CONSTRAINT card_category_rules_limit_non_negative
        CHECK (monthly_limit_minor_units IS NULL OR monthly_limit_minor_units >= 0)
);

CREATE INDEX idx_card_category_rules_card ON card_category_rules (card_id);

COMMENT ON TABLE card_category_rules IS
    'Per-card, per-category blocks and monthly caps. Enforced by CardAuthorizationPolicy in the authorization path (D3).';
