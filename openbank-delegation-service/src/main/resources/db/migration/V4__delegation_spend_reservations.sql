-- ADR-0249 D3 — the authoritative cumulative-spend counter.
--
-- `delegation_grants.daily_limit_*` and `monthly_limit_*` have existed since V1 and were enforced
-- nowhere: `DelegationGrant.withinLimits` only ever consulted `per_tx_limit_*`, which is why
-- #3613 made the offer API refuse the two fields rather than carry numbers nobody counted. This
-- table is what makes them mean something, so the refusal is lifted for grants that also carry a
-- money-moving capability.
--
-- Reserve-then-confirm, not count-after-settlement: a RESERVED row consumes headroom before the
-- money moves, so two concurrent payments cannot both pass a check that neither passes alone.
-- RELEASED gives the headroom back; CONFIRMED keeps it consumed.
--
-- CONCURRENCY. Two reserves that would jointly breach a ceiling must not both succeed, and this
-- service runs multiple replicas, so an in-JVM lock would guarantee nothing. The guarantee is at
-- the database:
--   1. every reserve takes `SELECT id FROM delegation_grants WHERE id = $1 FOR UPDATE` first, so
--      all reserves against ONE grant are serialised by Postgres — the count and the insert that
--      changes it are one indivisible step. Chosen over SERIALIZABLE (no retry loop to get wrong,
--      and no cross-grant contention) and over a unique/exclusion constraint (a ceiling is a SUM,
--      which no constraint can express);
--   2. `uq_delegation_spend_idempotency` makes a replayed key a database fact rather than a
--      read-then-write, so a retry can never double-count even if it lands on another replica.
--
-- WINDOWS. `created_at` is the window anchor, and the two windows are calendar day / calendar
-- month in **Europe/Prague** — see SpendWindows.ZONE for why the zone is fixed and not UTC. The
-- bounds are computed by the application and passed in, so this schema holds no zone assumption.
--
-- Amounts follow the existing convention of this schema: NUMERIC(20,6) plus a CHAR(3) currency,
-- exactly like the three ceiling columns they are compared against. Never a float.
--
-- Rollback:
--   DROP TABLE delegation_spend_reservations;

CREATE TABLE delegation_spend_reservations (
    id                  UUID PRIMARY KEY,
    grant_id            UUID NOT NULL REFERENCES delegation_grants(id) ON DELETE CASCADE,
    amount              NUMERIC(20, 6) NOT NULL,
    currency            CHAR(3) NOT NULL,
    idempotency_key     VARCHAR(200) NOT NULL,
    state               VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at          TIMESTAMPTZ,
    CONSTRAINT chk_delegation_spend_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_delegation_spend_state CHECK (state IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    CONSTRAINT chk_delegation_spend_settled_at CHECK ((state = 'RESERVED') = (settled_at IS NULL)),
    CONSTRAINT uq_delegation_spend_idempotency UNIQUE (grant_id, idempotency_key)
);

-- The exact shape of the counting query: one grant, one currency, the two counting states,
-- ordered by the window anchor.
CREATE INDEX idx_delegation_spend_window
    ON delegation_spend_reservations (grant_id, currency, state, created_at);

GRANT ALL ON ALL TABLES IN SCHEMA public TO openbank;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO openbank;
