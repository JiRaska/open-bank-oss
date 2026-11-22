-- Partition horizon for `transactions` (#17101).
--
-- V1 created RANGE partitions for 2025 and 2026 only, plus a DEFAULT catch-all. From 2027-01-01
-- every row would land in transactions_default, and once DEFAULT holds a 2027 row Postgres refuses
-- to create transactions_2027 (the new partition's range would overlap rows already in DEFAULT),
-- so the fix would become a data move under lock on a money-path table. This migration pre-creates
-- 2027 and 2028 while no such row can exist yet; TransactionPartitionMaintainer keeps the window
-- rolling at runtime (same shape as ledger V8 + JournalPartitionMaintainer).
--
-- Guard: if DEFAULT already holds rows for 2027/2028 the migration FAILS with an explicit message
-- instead of letting CREATE ... PARTITION OF die on a generic constraint error. It never moves
-- data itself — that needs an operator decision and a maintenance window.
--
-- Rollback note: forward-only and additive. While transactions_2027 / transactions_2028 are EMPTY,
-- rolling back is `DROP TABLE transactions_2028; DROP TABLE transactions_2027;` (rows for those
-- years then route to DEFAULT again — the original defect). Once either holds rows, do not drop:
-- `ALTER TABLE transactions DETACH PARTITION transactions_<year>` keeps the rows as a standalone
-- table. partition_lifecycle_audit is append-only evidence; leave it in place on rollback.
-- Never edit this file after it has been applied (Flyway checksum).

DO $$
DECLARE
    misrouted BIGINT;
BEGIN
    SELECT count(*) INTO misrouted
    FROM transactions_default
    WHERE booking_date >= DATE '2027-01-01' AND booking_date < DATE '2029-01-01';
    IF misrouted > 0 THEN
        RAISE EXCEPTION 'transactions_default holds % row(s) with booking_date in 2027-2028; refusing to create transactions_2027/2028 over them. Move those rows out of DEFAULT in a maintenance window, then re-run (#17101).', misrouted;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS transactions_2027 PARTITION OF transactions
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS transactions_2028 PARTITION OF transactions
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');

-- Immutable, append-only trail of every partition lifecycle action (mirrors ledger V8).
CREATE TABLE IF NOT EXISTS partition_lifecycle_audit (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    parent_table    VARCHAR(63)  NOT NULL,
    partition_name  VARCHAR(63)  NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    dry_run         BOOLEAN      NOT NULL DEFAULT false,
    executed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_partition_lifecycle_audit PRIMARY KEY (id),
    CONSTRAINT chk_partition_lifecycle_action
        CHECK (action IN ('CREATE', 'DETACH', 'DROP', 'DEFAULT_NONEMPTY', 'NOOP'))
);

CREATE INDEX IF NOT EXISTS idx_partition_audit_executed_at ON partition_lifecycle_audit (executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_partition_audit_partition   ON partition_lifecycle_audit (parent_table, partition_name);
