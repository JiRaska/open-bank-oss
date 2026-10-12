# Tax reporting: dead-lettered §38d withholding remittances

Operational runbook for `openbank.dlq.tax-reporting.withholding-remitted-in`. It lives here
because `generate-service-runbooks.py` owns `svc-tax-reporting.md`, and hand edits there count
as drift.

**Alerts.**
- `TaxWithholdingRemittanceDeadLettered` (critical): records arrived in the last hour.
- `TaxWithholdingDeadLetterNotEmpty` (warning): records are still held on the topic.

**What a record means.** tax-reporting-service could not decode an
`interest.withholding.remitted.v1` event, or could not record a valid one after bounded retries,
so it rethrew and the connector parked the original record. Nothing consumes the DLQ. `assemble`
totals only remittances the service observed. A missing remittance can understate the §38d return;
the period and amount of a malformed record may be unknown until source reconciliation. The source
group uses `auto.offset.reset: latest`, so the DLQ copy must be preserved for recovery.

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
   Record each `remittanceId`, its `dueDate` (which picks the filing period) and the reason when
   decodable. For malformed records whose period cannot be established, treat every potentially
   affected unfiled period as unresolved and reconcile against the source before filing.
3. **Fix the cause first.** For a valid record, repair the failed storage path before replay. For
   malformed input, establish the correct source values and approve a corrected replacement;
   verbatim replay of malformed bytes will dead-letter again. Do not discard the original DLQ
   record before reconciliation.
4. **Replay a valid record** by re-publishing it **verbatim** (same key, same payload, same `ce-type`
   header) to `openbank.interest.accrual.event`. Reconcile any corrected replacement with its
   original source and obtain money-path approval before publishing. Both consumers of that topic deduplicate on the
   remittance id:
   - tax-reporting's `observe` answers `duplicate` for a remittance it already holds.
   - interest-service's settlement consumer books with `idempotencyKey =
     interest-withholding-<remittanceId>`, and a 409 counts as an idempotent success.

   A replay therefore cannot double-count a remittance or book a second debit. It is still a
   write to a money-path topic: two people, attributable, through the approved change process.
   Never re-publish without the `ce-type` header, because both consumers ignore a record without
   it, and the replay then does nothing while looking as if it worked.
5. **Verify and retain the evidence.** Confirm each recovered `remittanceId` is listed under
   `GET /api/v1/tax/filings/{period}/remittances`, reconcile the source totals, and record the
   result through the approved tax process. Do not run `kafka-delete-records.sh` to clear this
   alert: it truncates an offset prefix and can erase unresolved records interleaved with recovered
   ones. Retain the original DLQ records while investigating. Treat the retained-record warning as
   an operational signal, not proof that a reconciled period remains incorrect.

The critical alert clears an hour after arrivals stop. The warning reflects retained records and
may remain after reconciliation; it can also clear on 30-day expiry without resolution. **Retention
and alert clearance are not proof of reconciliation.** Escalate any unresolved record before expiry.
