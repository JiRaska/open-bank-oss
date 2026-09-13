# Settlement audit recovery

Settlement state changes append `SETTLEMENT_STATE_CHANGED` version 1 to `settlement_outbox`
in the same database transaction as the aggregate. Origination includes `previousStatus: null`.
The origination writer refreshes the row after flushing so event amounts follow the stored
`NUMERIC(19,4)` representation. A repeated same-state write or a guarded late write emits no event. This covers both stored
settlement protocols; it does not make a recorded state proof of payment finality.

The scheduler claims rows atomically, sends raw JSON to `openbank.settlement.events`, and marks
SENT only after broker acknowledgement. A crash between acceptance and marking SENT can resend
an unchanged event. Consumers must deduplicate by `eventId`; concurrent dispatchers can deliver
state facts out of order. Audit ingestion must use the durable failure/chain-append implementation
before this producer is relied upon as evidence. Existing activity log messages are operational
telemetry, not the durable audit record.

`SettlementAuditUnsent` measures the oldest row without broker acknowledgement, including DEAD
and DISPATCHING. A collector failure is separately covered by workflow liveness. Inspect the
outbox status, attempt count and sanitized failure context, broker availability, topic existence,
producer Write and audit consumer Read ACLs, and mounted certificate validity. SENT proves broker
acceptance only; inspect the audit consumer lag, DLQ and stored event ID to establish ingestion.

After repairing the transport, requeue only reviewed FAILED/DEAD event IDs through the established
outbox recovery process. Preserve payload, event ID, aggregate ID and original occurrence time;
never mint replacement events or delete evidence to clear an alert. Stale DISPATCHING claims are
reclaimed by the scheduler. Coordinate with active dispatchers before manual row changes.

Deployment order: provision topic, identity, certificate projection and ACLs; deploy compatible
audit ingestion; apply additive migrations and roll out all settlement originators/workers. Old
writers can still change state without an audit row, so drain them before asserting complete
coverage. V4/V5 tables/columns and pending evidence must remain when rolling code back. Do not
backfill old state changes using today's time: their original transitions were not recorded.

This change records service-owned state facts (`actorType: SERVICE`), not the initiating person's
identity or full SCA evidence. Synthetic provenance was not persisted on settlement aggregates;
the inherited outbox default must not be used as proof that a settlement came from real traffic.
A production rollout still requires measured retention/capacity, resilient broker replication,
end-to-end authorization, legacy settlement reconciliation and recovery exercises. The checked-in
topic replica count follows the existing environment and is not a production availability proof.
