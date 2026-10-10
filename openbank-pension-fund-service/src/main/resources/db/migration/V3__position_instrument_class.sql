-- #12425: the instrument class of each recorded fund position (ECB/2018/2 asset categories), so
-- ČNB PEF 13-04 (fund loans) and the portfolio breakdown can be read from the positions a NAV was
-- struck on.
--
-- Existing positions get the EXPLICIT default 'UNCLASSIFIED': nothing recorded their class, and
-- guessing one would put a figure on a return that nobody measured. A report treats UNCLASSIFIED
-- as unknown (PEF 13-04 is refused while any closing position carries it) and an operator
-- reclassifies through position_classification_corrections, four-eyes. fund_nav_positions rows
-- stay write-once: a correction is a new row, and the latest APPROVED one is the effective class.
--
-- Rollback:
--   DROP TABLE position_classification_corrections;
--   ALTER TABLE fund_nav_positions DROP COLUMN instrument_class;

ALTER TABLE fund_nav_positions
    ADD COLUMN instrument_class VARCHAR(16) NOT NULL DEFAULT 'UNCLASSIFIED',
    ADD CONSTRAINT ck_fund_nav_positions_class CHECK (instrument_class IN
        ('DEPOSIT', 'DEBT_SECURITY', 'LOAN', 'EQUITY', 'FUND_SHARE', 'DERIVATIVE', 'OTHER', 'UNCLASSIFIED'));

CREATE TABLE position_classification_corrections (
    id           UUID PRIMARY KEY,
    position_id  UUID NOT NULL REFERENCES fund_nav_positions (id),
    nav_id       UUID NOT NULL REFERENCES fund_navs (id),
    from_class   VARCHAR(16) NOT NULL,
    to_class     VARCHAR(16) NOT NULL CHECK (to_class IN
        ('DEPOSIT', 'DEBT_SECURITY', 'LOAN', 'EQUITY', 'FUND_SHARE', 'DERIVATIVE', 'OTHER')),
    reason       VARCHAR(1024) NOT NULL,
    proposed_by  VARCHAR(256) NOT NULL,
    proposed_at  TIMESTAMPTZ NOT NULL,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    decided_by   VARCHAR(256),
    decided_at   TIMESTAMPTZ,
    -- Four-eyes, stated again where a writer that bypasses the aggregate cannot avoid it.
    CONSTRAINT ck_classification_four_eyes CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_classification_changes CHECK (to_class <> from_class)
);

-- At most one correction awaiting approval per position.
CREATE UNIQUE INDEX uq_classification_pending ON position_classification_corrections (position_id)
    WHERE status = 'PROPOSED';
CREATE INDEX idx_classification_position ON position_classification_corrections (position_id);
