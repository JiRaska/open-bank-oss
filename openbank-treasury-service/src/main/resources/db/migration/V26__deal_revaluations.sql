-- SPDX-License-Identifier: Apache-2.0
-- ADR-0344 D7/D8: one row per (deal, valuation date) — the fair value the risk engine reported on a
-- TIED_OUT snapshot, the carrying amount it was measured against, the cumulative fair-value
-- adjustment after it and the change that date posted through the matrix REVALUATION event. The
-- primary key IS the idempotency guard: a replay of the same date finds the row, and the loser of a
-- concurrent replay reads the winner's. Per deal, dates are strictly increasing (a per-deal advisory
-- lock orders the claim); an earlier date is refused, never back-filled. A data gap writes no row.
--
-- Rollback (nothing revalued): DROP TABLE deal_revaluations;
-- otherwise the journals in the ledger stay and only treasury's record of their measurement is lost.
CREATE TABLE deal_revaluations (
    deal_id          UUID           NOT NULL REFERENCES deals (deal_id),
    as_of            DATE           NOT NULL,
    snapshot_id      UUID           NOT NULL,
    fair_value       NUMERIC(24,2)  NOT NULL,
    carrying         NUMERIC(24,2)  NOT NULL,
    cumulative       NUMERIC(24,2)  NOT NULL,
    change_amount    NUMERIC(24,2)  NOT NULL,
    idempotency_key  VARCHAR(200),
    journal_id       UUID,
    recorded_at      TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (deal_id, as_of),
    -- A row is claimed (key set, no journal) BEFORE the ledger is asked, then the journal attached.
    CONSTRAINT deal_revaluations_journal_has_key CHECK (journal_id IS NULL OR idempotency_key IS NOT NULL)
);
CREATE INDEX idx_deal_revaluations_as_of ON deal_revaluations (as_of);
