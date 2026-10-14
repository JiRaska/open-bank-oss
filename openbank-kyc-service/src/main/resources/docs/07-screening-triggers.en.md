# Screening triggers (event-driven re-screening)

Perpetual-KYC slice 1 of [ADR 0256](../../../../docs/adr/0256-perpetual-kyc-event-driven-re-screening-between-periodic-reviews.md): when `openbank-sanctions-service` publishes a sanctions-list diff (`SANCTIONS_LIST_CHANGED` on `openbank.sanctions.screening.event`), kyc-service derives the candidate parties and opens one re-screening case per party **through the case coordinator** — triggers only ever OPEN a case; disposition stays with a human (ADR-0256 D2).

## Flow

1. **Consume** — `SanctionsListChangedConsumer` reads the channel `sanctions-list-changes-in` (explicit DLQ topic `openbank.dlq.kyc.sanctions-list-changes`; group id and `auto.offset.reset` come from the `kyc-service-msg-override` ConfigMap, the #686 apparatus). `SANCTIONS_LIST_CHANGE_STORM` events are ignored.
2. **Record** — `ScreeningTriggerService` inserts one durable `kyc_screening_triggers` row per (publication, party) — `insertIfAbsent`, so a Kafka redelivery resumes instead of duplicating. The full diff is retained on the row so "why is this customer under review" has a one-row answer.
3. **Open** — one `AML_ALERT` case per party via `POST /api/v1/case-coordinator/cases` (`case-coordinator-agent` in the `platform` namespace, port 8146, supplied as `CASE_COORDINATOR_URL`).
4. **Fail durably** — coordinator 429/503/timeout records an `OPEN_FAILED` trigger, and once attempts exhaust, a `KYC_COORDINATOR_OPEN_FAILED` outbox row written in the SAME transaction as the state flip. A missing `CASE_COORDINATOR_URL` maps every trigger to the durable `NOT_CONFIGURED` path — never to a silent skip.
5. **Retry** — `ScreeningTriggerRetryJob` re-attempts backed-off `OPEN_FAILED` rows, so the loop closes when the coordinator recovers.

## Known v1 granularity

The candidate set is **not** diff-scoped per entry: kyc-service persists no party ↔ list-entry mapping, so v1 triggers on parties with a non-terminal KYC case whenever a diff publication lands (bounded by the in-flight book). Per-entry scoping needs a party ↔ matched-entry index (a follow-up slice); the trigger rows retain the full diff for audit either way.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `KYC_SCREENING_TRIGGERS_ENABLED` | `false` | Master switch. Ships **disabled** per the contract's rollout sequence (Pact verification, sandbox exercise, operator-task metrics land before enablement). |
| `KYC_SCREENING_TRIGGERS_CANDIDATE_LIMIT` | `500` | Cap on candidate parties per publication chunk. |
| `KYC_SCREENING_TRIGGERS_RETRY_ENABLED` | `true` | Scheduled retry of `OPEN_FAILED` rows. |
| `KYC_SCREENING_TRIGGERS_RETRY_INTERVAL` | `1m` | Retry backoff. |
| `KYC_SCREENING_TRIGGERS_RETRY_BATCH_SIZE` | `100` | Rows claimed per retry pass. |
| `CASE_COORDINATOR_URL` | (empty → `NOT_CONFIGURED`) | Case-coordinator base URL; set in gitops to `http://case-coordinator-agent.platform.svc:8146`. |

## Operations notes

- **A new Kafka channel is two deploys, not one** (.github/CLAUDE.md): the `sanctions-list-changes-in` group id / offset overrides land in the msg-override ConfigMap only after an image carrying the channel's `connector:` is deployed; until then the consumer runs on the fallback group id (`quarkus.application.name`), which the `kyc-service` KafkaUser is granted Read on.
- The consumer's failure path rethrows to the connector so infrastructure failures dead-letter; coordinator-call failures are the row + outbox event's job, not Kafka's.
- The `kyc_screening_triggers` table (Flyway V12) is append-audited: `state` transitions are updates, the diff columns are write-once.

## Recovery ownership and duplicate scope

Each new PENDING row carries an initial ten-minute lease and attempt number. A worker atomically claims only an expired lease; result updates compare the claimed attempt and lease in the same transaction as any failure outbox row. A stale worker cannot overwrite a newer result. Historical PENDING rows without a lease become eligible only after ten minutes from creation; new unleased inserts are rejected. Terminal OPEN_FAILED rows have no next-attempt timestamp and are never automatically retried.

New leased work allows at most five coordinator calls; the attempt counter includes a call whose outcome was lost during a crash. Recovery after the fifth abandoned attempt takes a sixth fencing claim and records counter six in the parked row/event, without a sixth coordinator call. Operators must reconcile an ambiguous final attempt rather than blindly retry it.

Coordinator calls retain the existing deterministic workflow id and Temporal REJECT_DUPLICATE policy. This protects redelivery while the workflow history remains retained; it is not a permanent exactly-once guarantee after Temporal history expires. No downstream idempotency API or retention policy is changed here.

The candidate limit is a page size. Keyset continuation visits the complete active book, rather than silently truncating it to the first page. Rollback disables screening triggers; retain pending/failure rows for reconciliation instead of deleting them.

The five-call budget applies to new leased inserts. Legacy rows with attempt zero and no lease do not establish whether an old coordinator call occurred; their historical side effects remain ambiguous and must be reconciled against coordinator history. Recovery does not prove that the total historical call count was at most five.
