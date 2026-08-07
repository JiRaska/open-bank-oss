-- Records WHETHER `occurred_at` is the producer's own event time or the consumer's ingest time
-- standing in for it (#3883).
--
-- WHY THIS EXISTS. AuditConsumer read the event time as
--   node["occurredAt"] ?: Instant.now(clock)
-- with nothing recording which branch had been taken. `occurredAt` IS the fleet's canonical key
-- (it is declared on com.openbank.libs.domain.event.DomainEvent, and 14 of the 21 consumed topics
-- emit it), so the read is correct — but 7 topics emit no event time at all, or name it something
-- else: clearing.batch.event, dispute.events, statement.event, sanctions.screening.event, six of
-- lending.events' payloads, sepa-payment's Temporal activity path, and documents.document.event
-- which names it `at`. For every one of those the row asserted, indistinguishably from a real
-- measurement, that the operation happened when the consumer got round to it. Under consumer lag
-- or a replay that is arbitrarily wrong, and it is the GDPR Art. 30 "when" dimension and the basis
-- of DORA Art. 17 reconstruction.
--
-- "When it happened" and "when we recorded it" are different facts. `recorded_at` already holds
-- the second one and is kept — this column says whether the first is real.
--
-- WHY NO BACKFILL. Two independent reasons, either sufficient:
--   * `audit_entries` carries `CREATE RULE no_update_audit ... DO INSTEAD NOTHING` (V2), so an
--     UPDATE against it succeeds and changes nothing. A backfill migration would look like it had
--     worked.
--   * there is nothing to backfill from. The producer key that was or was not present was never
--     stored in a column, only inside the verbatim `payload` JSON, and re-deriving it row by row
--     would still be a rewrite of audit data — which is what tamper-evidence exists to prevent.
-- So pre-V11 rows stay NULL = unknown, and the read side maps NULL to the weaker claim (INGEST):
-- those rows may hold ingest time and cannot prove otherwise. Same choice as V10's hash_version —
-- record the boundary, never rewrite history.
--
-- TAMPER-EVIDENCE. Not part of chainHash(), so no existing record_hash changes and no future row's
-- hash is affected: the column is derived from the producer's raw event JSON, which the chain
-- already covers via the payload hash (same argument as the ADR-0226 channel columns in V9).
--
-- Rollback: ALTER TABLE audit_entries DROP COLUMN occurred_at_source;
--   (safe — no row's record_hash, occurred_at or recorded_at is modified by this migration.)
ALTER TABLE audit_entries ADD COLUMN IF NOT EXISTS occurred_at_source VARCHAR(8);

COMMENT ON COLUMN audit_entries.occurred_at_source IS
  'Provenance of occurred_at. EVENT = the producer sent a parseable occurredAt. '
  'INGEST = it did not, so occurred_at is the time this service received the event (an upper '
  'bound, not a measurement). NULL = row written before this column existed; treat as INGEST.';

-- Partial index: the operational question is "which rows do NOT carry a real event time", and the
-- EVENT rows are expected to be the overwhelming and growing majority.
CREATE INDEX IF NOT EXISTS idx_audit_entries_occurred_at_source
  ON audit_entries(occurred_at_source) WHERE occurred_at_source <> 'EVENT';
