-- ADR-0117 hardening: evidence chain (tamper-evident) + remediation outcome (first increment).
-- Rollback: ALTER TABLE disputes DROP COLUMN remediation_outcome, DROP COLUMN remediation_amount;
--           DROP TYPE remediation_outcome;
--           ALTER TABLE dispute_evidence DROP COLUMN sequence, DROP COLUMN prev_hash, DROP COLUMN record_hash;
--           DROP INDEX idx_evidence_dispute_sequence;

CREATE TYPE remediation_outcome AS ENUM ('UPHELD', 'REJECTED', 'PARTIAL');

ALTER TABLE disputes
    ADD COLUMN remediation_outcome remediation_outcome,
    ADD COLUMN remediation_amount  NUMERIC(20, 4);

-- Evidence chain (ADR-0117 §2, ADR-0133 pattern applied per-dispute rather than globally).
-- `sequence` is the 0-based monotonic position of the item within ITS dispute's chain;
-- `prev_hash` is the record_hash of the previous item (or the genesis constant for sequence 0);
-- `record_hash` commits to (prev_hash + this item's content) — see EvidenceChain.kt.
ALTER TABLE dispute_evidence
    ADD COLUMN sequence    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN prev_hash   VARCHAR(64),
    ADD COLUMN record_hash VARCHAR(64);

-- One chain per dispute: sequence must be unique within a dispute so the chain has no gaps/dupes.
CREATE UNIQUE INDEX idx_evidence_dispute_sequence ON dispute_evidence(dispute_id, sequence);

-- Backfill note: pre-migration evidence rows (if any existed) get sequence=0/prev_hash=NULL/
-- record_hash=NULL by the DEFAULT above and are NOT retroactively chained — mirrors ADR-0133's
-- "unchained" handling of pre-chain audit rows. EvidenceChain.verify() will treat a row with a
-- null record_hash as a broken/unverifiable link; this dispute-service deployment has no
-- pre-existing evidence data (dispute_evidence has been append-only since V1 with no prior
-- production traffic), so no separate backfill script is needed.
