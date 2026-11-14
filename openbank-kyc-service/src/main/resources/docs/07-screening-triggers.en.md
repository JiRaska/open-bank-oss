# Screening triggers (event-driven re-screening)

Perpetual-KYC slice 1 of [ADR 0256](../../../../docs/adr/0256-perpetual-kyc-event-driven-re-screening-between-periodic-reviews.md): when `openbank-sanctions-service` publishes a sanctions-list diff (`SANCTIONS_LIST_CHANGED` on `openbank.sanctions.screening.event`), kyc-service derives the candidate parties and opens one re-screening case per party **through the case coordinator** — triggers only ever OPEN a case; disposition stays with a human (ADR-0256 D2).

## Flow

1. **Consume** — `SanctionsListChangedConsumer` reads the channel `sanctions-list-changes-in` (explicit DLQ topic `openbank.dlq.kyc.sanctions-list-changes`; group id and `auto.offset.reset` come from the `kyc-service-msg-override` ConfigMap, the #686 apparatus). `SANCTIONS_LIST_CHANGE_STORM` events are ignored. The event type is read from the `ce-type` Kafka **header**: the topic is outbox-relayed and its bodies carry no `eventType` field (`openbank-contracts/openbank-sanctions-service/asyncapi.yaml`). Every record is counted in `openbank.kyc.sanctions_list_change_events{outcome}` — `handled`, `storm`, `recorded_while_disabled`, `ignored` (the screening events sharing the topic), `malformed`, `unrecognised`.
2. **Record** — `ScreeningTriggerService` inserts one durable `kyc_screening_triggers` row per (publication, party) — `insertIfAbsent`, so a Kafka redelivery resumes instead of duplicating. The full diff is retained on the row so "why is this customer under review" has a one-row answer.
3. **Open** — one `AML_ALERT` case per party via `POST /api/v1/case-coordinator/cases` (`case-coordinator-agent` in the `platform` namespace, port 8146, supplied as `CASE_COORDINATOR_URL`).
4. **Fail durably** — coordinator 429/503/timeout records an `OPEN_FAILED` trigger, and once attempts exhaust, a `KYC_COORDINATOR_OPEN_FAILED` outbox row written in the SAME transaction as the state flip. A missing `CASE_COORDINATOR_URL` maps every trigger to the durable `NOT_CONFIGURED` path — never to a silent skip.
5. **Retry** — `ScreeningTriggerRetryJob` re-attempts backed-off `OPEN_FAILED` rows, so the loop closes when the coordinator recovers. It also claims expired PENDING leases and the deferred rows recorded while the switch was off.

## Coordinator HTTP outcomes

The AML case-opening adapter classifies mapped HTTP failures inside its existing retry boundary:

- `201` opens the case only when the response contains a nonblank `caseId`. A missing identifier is rejected.
- `409` means the coordinator already has the case (`AlreadyExists`); it is not retried.
- Other deterministic `4xx` responses, including `400` and `403`, are rejected without retry. `429` is the exception: it remains retryable.
- `429`, `5xx` and transport failures retain the existing bounded retry policy: at most three HTTP attempts per adapter invocation. The circuit breaker and timeout may stop an invocation earlier. This bound is separate from the durable trigger recovery budget below.

Provider responses are closed after classification, including responses recovered from the REST client's exception mapper. Failure outcome details contain only the HTTP status or exception type, never a provider response body, URL or exception message. These classifications preserve the existing durable trigger handling; they do not enable screening triggers or change coordinator idempotency.

## Known v1 granularity

The candidate set is **not** diff-scoped per entry: kyc-service persists no party ↔ list-entry mapping, so v1 triggers on parties with a non-terminal KYC case whenever a diff publication lands (bounded by the in-flight book). Per-entry scoping needs a party ↔ matched-entry index (a follow-up slice); the trigger rows retain the full diff for audit either way.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `KYC_SCREENING_TRIGGERS_ENABLED` | `false` | Master switch, the only one: it governs every path that opens a case (the consumer's first attempt and `ScreeningTriggerRetryJob`). Ships **disabled** per the contract's rollout sequence (Pact verification, sandbox exercise, operator-task metrics land before enablement). Off still records: see "The master switch" below. |
| `KYC_SCREENING_TRIGGERS_CANDIDATE_LIMIT` | `500` | Cap on candidate parties per publication chunk. |
| `KYC_SCREENING_TRIGGERS_RETRY_INTERVAL` | `1m` | Retry backoff. |
| `KYC_SCREENING_TRIGGERS_RETRY_BATCH_SIZE` | `100` | Rows claimed per retry pass. |
| `CASE_COORDINATOR_URL` | (empty → `NOT_CONFIGURED`) | Case-coordinator base URL; set in gitops to `http://case-coordinator-agent.platform.svc:8146`. |

## Operations notes

- **A new Kafka channel is two deploys, not one** (.github/CLAUDE.md): the `sanctions-list-changes-in` group id / offset overrides land in the msg-override ConfigMap only after an image carrying the channel's `connector:` is deployed; until then the consumer runs on the fallback group id (`quarkus.application.name`), which the `kyc-service` KafkaUser is granted Read on.
- **`outcome="unrecognised"` above zero means the consumer and the producer disagree about the wire shape** (a missing `ce-type` header or a type this consumer does not know). The records are acked, so nothing else — lag, DLQ, error rate — shows it; with the loop enabled, a publication on the sanctions side and no `handled` increment here is the same signal from the other end.
- The consumer's failure path rethrows to the connector so infrastructure failures dead-letter; coordinator-call failures are the row + outbox event's job, not Kafka's.
- The `kyc_screening_triggers` table (Flyway V12) is append-audited: `state` transitions are updates, the diff columns are write-once.

## Backlog metrics and alerts

Issue #15961. The backlog is the set of `kyc_screening_triggers` rows in PENDING, OPEN_FAILED or DEFERRED: list changes that have not yet reached the customer as an opened case. `RescreenBacklogGauge` samples it from the database once a minute (`openbank.kyc.screening-triggers.backlog-sample-interval`, default `1m`) with one aggregate over the leading `state` column of `ix_kyc_screening_triggers_retry`, so every pod publishes the same value and a restart loses nothing.

| Metric | Meaning | Cold pod |
|---|---|---|
| `openbank_kyc_rescreen_backlog_pending{service="kyc"}` | Rows PENDING, OPEN_FAILED or DEFERRED (in flight, retrying, parked, or recorded while off). | NaN until the first sample |
| `openbank_kyc_rescreen_backlog_oldest_age_seconds{service="kyc"}` | `now() - created_at` of the oldest such row, by the database clock, from enqueue time. `0` only when the backlog is empty. Never from `next_attempt_at`, which each retry pushes forward. | NaN until the first sample |
| `openbank_kyc_rescreen_loop_disabled_with_pending{service="kyc"}` | `1` when `enabled` is false and the backlog is non-empty, else `0`. A separate series, so "off and empty" and "off with work left" never share a value. | NaN until the first sample |

NaN, not 0 and not an epoch-derived age: a cold pod neither claims an empty healthy queue nobody measured nor reports decades. A failed sample keeps the previous values, so a database outage cannot clear a firing alert; the `kyc-rescreen-backlog-sample` heartbeat goes stale instead.

**Deadline: 24 hours.** ADR-0256 promises a list change reaches the customer within the refresh cycle, and the lists refresh daily. The healthy worst case is about an hour (five attempts with a 5-minute-times-attempt backoff), so a row older than a day will be overtaken by the next list change.

- `KycRescreenBacklogOverdue` (critical): oldest age above 86400 s for 15 minutes, unless the loop is disabled with pending work.
- `KycRescreenLoopDisabledWithPending` (warning): the disabled-with-pending series is 1 for 30 minutes.


**The master switch (issue #15961).** `KYC_SCREENING_TRIGGERS_ENABLED` decides whether the loop *acts*; the queue records either way.

| Switch | `SANCTIONS_LIST_CHANGED` arrives | Retry job tick |
|---|---|---|
| off | One row in state **`DEFERRED`** per candidate party (`attempts` 0, due now), no coordinator call; counted `recorded_while_disabled`. Coalesced: a party already waiting gets no second row (partial unique index from V14). | Claims nothing. Rows left from an earlier on-period (leases, `OPEN_FAILED` retries) wait too. |
| on | Unchanged: row inserted with a lease, case opened at once; counted `handled`. | Claims up to `KYC_SCREENING_TRIGGERS_RETRY_BATCH_SIZE` due rows (deferred, expired leases, backed-off `OPEN_FAILED`) and opens each once; a `DEFERRED` row moves to PENDING with a lease in the same atomic claim. |
| image rolled back to pre-#15961 code | Old behaviour (dropped while off). | The old job selects and claims only `PENDING`/`OPEN_FAILED`, so `DEFERRED` rows are invisible to it and stay inert; they drain when this code returns. Held by `ScreeningTriggerMasterSwitchOffIT`, which runs main's claim SQL verbatim. |

Growth while off is bounded by the number of active KYC parties, not by time: one deferred row per party however many publications arrive (lists refresh about daily). A month off adds at most one row per active party, plus nothing for the parties already waiting. The row keeps the first publication's diff; the case it opens screens against the current lists. Drain after turning the switch on: at most batch size per interval per pod, by default 100 a minute (6,000 an hour) with the single replica, and a coordinator `429`/`503` moves the row onto the 5-minute-times-attempt backoff instead of retrying hot. After a long off period `KycRescreenBacklogOverdue` fires until the oldest deferred row is opened, because those changes did miss the 24-hour promise.

The first two alerts cannot fire for the shipped default (loop off, table empty: age 0, disabled-with-pending 0). Tests: `openbank-infra/tests/promtool/kyc_rescreen_backlog_alerts_test.yaml`. Runbook: [05-operations](05-operations.en.md#re-screening-backlog).

## Recovery ownership and duplicate scope

Each new PENDING row carries an initial ten-minute lease and attempt number. A worker atomically claims only an expired lease; result updates compare the claimed attempt and lease in the same transaction as any failure outbox row. A stale worker cannot overwrite a newer result. Historical PENDING rows without a lease become eligible only after ten minutes from creation; new unleased inserts are rejected. Terminal OPEN_FAILED rows have no next-attempt timestamp and are never automatically retried.

New leased work allows at most five coordinator calls; the attempt counter includes a call whose outcome was lost during a crash. Recovery after the fifth abandoned attempt takes a sixth fencing claim and records counter six in the parked row/event, without a sixth coordinator call. Operators must reconcile an ambiguous final attempt rather than blindly retry it.

Coordinator calls retain the existing deterministic workflow id and Temporal REJECT_DUPLICATE policy. This protects redelivery while the workflow history remains retained; it is not a permanent exactly-once guarantee after Temporal history expires. No downstream idempotency API or retention policy is changed here.

The candidate limit is a page size. Keyset continuation visits the complete active book, rather than silently truncating it to the first page. Rollback disables screening triggers; retain pending/failure rows for reconciliation instead of deleting them.

The five-call budget applies to new leased inserts. Legacy rows with attempt zero and no lease do not establish whether an old coordinator call occurred; their historical side effects remain ambiguous and must be reconciled against coordinator history. Recovery does not prove that the total historical call count was at most five.
