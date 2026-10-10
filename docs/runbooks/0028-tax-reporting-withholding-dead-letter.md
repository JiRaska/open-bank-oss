# Tax reporting: dead-lettered §38d withholding remittances

Operational runbook for `openbank.dlq.tax-reporting.withholding-remitted-in`. It lives here
because `generate-service-runbooks.py` owns `svc-tax-reporting.md`, and hand edits there count
as drift.

**Alerts.**
- `TaxWithholdingRemittanceDeadLettered` (critical): records arrived in the last hour.
- `TaxWithholdingDeadLetterNotEmpty` (warning): records are still held on the topic.

**What a record means.** tax-reporting-service could not record an
`interest.withholding.remitted.v1` event even after its bounded retries, so it rethrew and the
connector parked the record. Nothing consumes the DLQ. `assemble` totals only remittances the
service observed, so **the §38d return for that month understates the tax withheld** until the
record is replayed. No other signal shows this. The source group uses `auto.offset.reset: latest`,
so the DLQ copy is the one to recover from.

1. **Freeze the affected period.** Do not assemble or file a period while its remittances are on
   the DLQ. If the period is already `ASSEMBLED`, it has to be redone after the replay. If it is
   already `FILED`, the replay leads to a corrective return (*dodatečné vyúčtování*), so tell
   the tax owner.
2. **Read the records before touching anything.** Each record keeps its original key, payload
   and `ce-type` header, plus SmallRye's `dead-letter-reason` / `dead-letter-cause` headers:
   ```
   kubectl -n messaging exec -it openbank-cluster-kafka-0 -- bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 --topic openbank.dlq.tax-reporting.withholding-remitted-in \
     --from-beginning --property print.headers=true --property print.key=true --timeout-ms 10000
   ```
   Record each `remittanceId`, its `dueDate` (which picks the filing period) and the reason.
3. **Fix the cause first.** It is almost always tax-reporting-db: unavailable, out of disk, or
   failing over (`kubectl cnpg status tax-reporting-db -n tax-reporting`). Replaying into a
   broken database only dead-letters the record again.
4. **Replay** by re-publishing each record **verbatim** (same key, same payload, same `ce-type`
   header) to `openbank.interest.accrual.event`. Both consumers of that topic deduplicate on the
   remittance id:
   - tax-reporting's `observe` answers `duplicate` for a remittance it already holds.
   - interest-service's settlement consumer books with `idempotencyKey =
     interest-withholding-<remittanceId>`, and a 409 counts as an idempotent success.

   A replay therefore cannot double-count a remittance or book a second debit. It is still a
   write to a money-path topic: two people, attributable, through the approved change process.
   Never re-publish without the `ce-type` header, because both consumers ignore a record without
   it, and the replay then does nothing while looking as if it worked.
5. **Verify, then delete.** Confirm each `remittanceId` is now listed under
   `GET /api/v1/tax/filings/{period}/remittances`. Then delete the replayed records so the warning
   clears:
   `bin/kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file <file>`,
   with the offset set to one past the last replayed record. Records that were not replayed must
   stay on the topic.

The critical alert clears an hour after arrivals stop. The warning clears only when the topic is
empty, or after its 30-day retention. **Retention is not a resolution:** an expired record is a
remittance permanently missing from a return.
