-- Records WHO supplied `source_service`: the producer itself, or the broker topic standing in for
-- it (#3994). The attribution counterpart of V11's `occurred_at_source`.
--
-- WHY THIS EXISTS. AuditConsumer read the producing service as
--   node["sourceService"] ?: "unknown"
-- with nothing recording which branch had been taken. Measured on the live audit database:
--   SELECT source_service, count(*) FROM audit_entries GROUP BY 1;
--     unknown       | 1353
--     customer-edge |  421      <- only TWO distinct values in the entire table
-- 76% of the evidentiary record cannot name the service that produced it, because customer-edge is
-- the only producer in the fleet that populates the field. That covers balances, holds,
-- transactions, KYC decisions, account lifecycle, party merges and consent — and `source_service`
-- is not decoration: it is chain-hashed into `record_hash`, and it is returned to data subjects by
-- the GDPR Art. 15 customer access log and to grantors by the ADR-0232 D5 transparency query.
--
-- WHAT CHANGES. The consumer now reads the Kafka topic (it took `payload: String`, so it could not
-- see one) and resolves the producer through a verified topic -> service table. That retires
-- essentially the whole `unknown` bucket for NEW rows.
--
-- WHY THE COLUMN, AND NOT JUST THE BETTER VALUE. A topic-derived attribution is sound but it is
-- not the producer's own assertion, and writing it into the same column with no marker would make
-- a consumer-supplied value indistinguishable from a producer-supplied one — converting a visibly
-- missing attribution into an invisibly derived one. In a tamper-evident store meant to answer
-- "who did this, from where", that is a worse failure than the gap it closes, and a much harder
-- one to ever notice again. This column is the record of which it was.
--
-- WHY NO BACKFILL. Both reasons from V11 apply unchanged, either sufficient:
--   * `audit_entries` carries `CREATE RULE no_update_audit ... DO INSTEAD NOTHING` (V2), so an
--     UPDATE against it succeeds and changes nothing. A backfill migration would look like it had
--     worked and would have done nothing at all.
--   * there is nothing to backfill FROM. The topic a row arrived on was never stored in any
--     column, and re-deriving 1353 rows' provenance would be a rewrite of audit data — which is
--     what tamper-evidence exists to prevent.
-- So pre-V13 rows stay NULL, and the read side maps NULL to the weakest claim (ABSENT): those rows
-- cannot say who supplied `source_service`, and for the overwhelming majority the answer is that
-- nobody did. Same choice as V10's hash_version and V11 — record the boundary, never rewrite
-- history.
--
-- TAMPER-EVIDENCE. Not part of chainHash(), so no existing record_hash changes and no future row's
-- hash is affected. `source_service` itself IS chain-hashed and continues to be, unchanged — this
-- column describes where the consumer obtained that value, it does not assert a new evidential
-- fact (same argument as the ADR-0226 channel columns in V9 and occurred_at_source in V11).
--
-- Rollback: DROP INDEX IF EXISTS idx_audit_entries_source_service_source;
--           ALTER TABLE audit_entries DROP COLUMN source_service_source;
--   (safe — no row's record_hash, source_service, occurred_at or recorded_at is modified here.)
ALTER TABLE audit_entries ADD COLUMN IF NOT EXISTS source_service_source VARCHAR(8);

COMMENT ON COLUMN audit_entries.source_service_source IS
  'Provenance of source_service. EVENT = the producer sent a sourceService field. '
  'TOPIC = it did not, so the producing service was resolved from the Kafka topic the record '
  'arrived on (sound, but not the producer''s own claim). ABSENT = neither, so source_service '
  'holds the "unknown" sentinel and attributes nothing. NULL = row written before this column '
  'existed; treat as ABSENT.';

-- Partial index: the operational question is "which rows were NOT attributed by their producer",
-- and EVENT rows are expected to become the majority as producers are fixed. TOPIC rows are a
-- correctly attributed row AND an outstanding producer gap, so they must stay queryable.
CREATE INDEX IF NOT EXISTS idx_audit_entries_source_service_source
  ON audit_entries(source_service_source) WHERE source_service_source <> 'EVENT';
