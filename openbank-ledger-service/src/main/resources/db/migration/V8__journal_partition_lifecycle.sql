-- Partition lifecycle: roll-forward future partitions + immutable audit log.
--
-- Background: journal_entries is RANGE-partitioned by entry_date with hardcoded partitions for
-- 2024/2025/2026 + a DEFAULT catch-all (V1). Without roll-forward, every entry after 2026-12-31
-- silently lands in journal_entries_default, which defeats partition pruning and blocks later
-- CREATE/ATTACH of a 2027 partition. The runtime JournalPartitionMaintainer keeps the horizon
-- healthy; the statements below establish a sane baseline immediately after migration.

-- Pre-create the near-term horizon (idempotent; the maintainer also ensures these at runtime).
CREATE TABLE IF NOT EXISTS journal_entries_2027 PARTITION OF journal_entries
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE IF NOT EXISTS journal_entries_2028 PARTITION OF journal_entries
    FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');

-- Immutable, append-only audit trail of every partition lifecycle action. Never UPDATEd or
-- DELETEd by the application. Supports regulatory record-keeping (CZ Zákon o účetnictví; AML 10y)
-- by making creation, detach, drop and guard-alert events durable and queryable.
CREATE TABLE partition_lifecycle_audit (
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

CREATE INDEX idx_partition_audit_executed_at ON partition_lifecycle_audit (executed_at DESC);
CREATE INDEX idx_partition_audit_partition   ON partition_lifecycle_audit (parent_table, partition_name);
