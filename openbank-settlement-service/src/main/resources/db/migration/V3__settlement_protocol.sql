-- Rollback: disable projection originations, drain or reconcile all LEDGER_PROJECTION histories,
-- then roll back binaries. Retain the protocol column and values; do not rewrite in-flight protocols.
-- Existing rows and legacy writers retain their protocol. Enable projection originations only
-- after every serving originator/worker understands this column and balance projection is ready.
-- Roll back the flag first, drain new workflows before binary rollback, and retain this column.
-- Never convert an in-flight legacy settlement: its direct movements require reconciliation.
ALTER TABLE settlements ADD COLUMN settlement_protocol VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
    CHECK (settlement_protocol IN ('LEGACY', 'LEDGER_PROJECTION'));
