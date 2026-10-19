# Operations

## Build & run

```bash
# Build (fast-jar)
./gradlew :openbank-card-processing-service:quarkusBuild

# Dev mode — OIDC disabled, sandbox acquirer enabled
./gradlew :openbank-card-processing-service:quarkusDev
```

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/card-authorizations/...` | 8157 | business REST API |
| `/api/v1/sandbox/acquirer/purchase` | 8157 | sandbox acquirer (404 unless enabled) |
| `/api/docs` | 8157 | Swagger UI |
| `/q/openbank/docs` | 8085 | this documentation |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus |

## Configuration

| Key / env | Default | Purpose |
|---|---|---|
| `CARD_HOLD_EXPIRY_DAYS` | `7` | how long an unpresented hold lives |
| `CARD_HOLD_SWEEP_CRON` | `0 */15 * * * ?` | expiry sweep schedule |
| `CARD_HOLD_SWEEP_BATCH` | `200` | holds released per sweep run |
| `CARD_LEDGER_POSTING_ENABLED` | `true` | post cleared spend to transaction-service (`false` ⇒ `SKIPPED_DISABLED`) |
| `CARD_FRAUD_SCORING_ENABLED` | `true` | shadow scoring (`false` ⇒ `SKIPPED_DISABLED`) |
| `CARD_SANDBOX_ACQUIRER_ENABLED` | `false` | **never on in a deployed environment** — it moves money end to end |
| `CARD_DEFAULT_CURRENCY` | `CZK` | card-issuance does not yet publish a per-card currency |
| `CARD_SCHEME_BIN_LOOKUP` | `simulator` | `simulator` / `visa` / `mastercard` |
| `CARD_SCHEME_TOKENISATION` | `simulator` | vendor values answer `NOT_BOUND` |
| `CARD_SCHEME_DISPUTE` | `simulator` | vendor values answer `NOT_BOUND` |
| `VISA_API_KEY`, `VISA_KEYSTORE_*`, `VISA_TRUSTSTORE_*` | empty | Visa sandbox credentials, supplied from OpenBao |
| `MASTERCARD_CONSUMER_KEY`, `MASTERCARD_SIGNING_KEY`, `MASTERCARD_API_URL` | empty | Mastercard sandbox credentials (signing key = base64 PKCS#8 RSA), from OpenBao |
| `CARD_ISSUANCE_SERVICE_URL`, `TRANSACTION_SERVICE_URL`, `FRAUD_SERVICE_URL` | localhost ports | downstream services |
| `AUTHZ_ENFORCE` | `false` | OPA decision is advisory until flipped |

Client timeouts: card-issuance 2 s connect / 3 s read (fail closed), fraud-service 2 s / 2 s, transaction-service 3 s / 10 s, Visa and Mastercard 3 s / 5 s.

No credential lives in `application.yaml`; local-dev placeholders (`CHANGE_ME_LOCAL_DEV_ONLY`) must be overridden.

## Scheduled jobs

| Job | Schedule | Liveness |
|---|---|---|
| `card-processing-outbox-dispatcher` | every 5s (`openbank.outbox.poll-interval`) | outbox backlog + dead-letter gauges |
| `card-processing-hold-expiry` | cron, every 15 min | workflow liveness gauge registered at startup (ADR-0237) |

Both are `suspend fun`; in `%test` the scheduler is disabled and the tests drive them explicitly.

## Metrics

| Metric | Tags |
|---|---|
| `openbank.card.processing.authorizations` | `approved`, `reason` |
| `openbank.card.processing.presentments` | `fully_cleared` |
| `openbank.card.processing.clearing.conflicts` | — (clearings, reversals and expiries that lost a concurrent-write race and were re-evaluated; an expiry that loses twice is left for the next sweep) |
| `openbank.card.processing.hold.releases` | `kind` (`REVERSAL` / `EXPIRY`) |
| `openbank.card.processing.ledger.postings` | `outcome` (`POSTED` / `SKIPPED_DISABLED` / `FAILED` / `UNKNOWN`) |
| `openbank.card.processing.ledger.posting.unposted` (gauge) | clearings still owed a ledger posting |
| `openbank.card.processing.ledger.posting.exhausted` (gauge) | clearings that used up their attempts; alert `CardLedgerPostingExhausted` |
| `openbank.card.processing.fraud.scores` | `outcome` (`SCORED` / `SKIPPED_DISABLED` / `FAILED`) |

Shadow fraud scoring sends fraud-service its real `POST /api/v1/fraud/score` contract: `accountId`, `amount` in major units by the currency's minor-unit digits, `currency` and `rail = "CARD"`. It reads `verdict` and an integer `score`. It never blocks an authorisation. A failure (any 4xx/5xx, timeout or unreadable body) is counted as `outcome=FAILED` and logged at WARN, at most once a minute with the number of suppressed failures. Before 2026-10-04 the client sent `currencyCode`, no `rail` and minor-unit amounts, and read `decision`, so every shadow score failed silently. The pacts `pacts/openbank-card-processing-service-openbank-fraud-service.json` and `…-openbank-transaction-service.json` now pin both calls, including a recorded 401.

| `openbank.card.token.provisions` | `scheme`, `refusal` |
| `openbank.card.token.status.changes` | `scheme`, `status`, `refusal` |
| `openbank.card.token.reads` | `source` (`NETWORK` / `LOCAL_MIRROR`) |
| `openbank.card.disputes.opened` | `scheme`, `refusal` |
| `openbank.card.dispute.evidence` | `refusal` |
| `openbank.card.dispute.terminal.mismatches` | `scheme`, `stored`, `reported` |

`refusal` is `none` on success. `scheme="NONE"` marks a refusal decided before any network was asked (unknown or non-ACTIVE card, card-issuance unreachable, ineligible dispute) — the same value on the token and dispute counters. All carry `service="card-processing"`, as do the outbox backlog and dead-letter gauges.

## Alerts

Defined in `openbank-infra/gitops/components/observability/prometheus-rules-card-money-path.yaml`, with promtool tests in `openbank-infra/tests/promtool/card_money_path_alerts_test.yaml`:

| Alert | Fires when | Severity |
|---|---|---|
| `CardClearedButNotPosted` | any `SKIPPED_DISABLED` ledger posting in 1h | critical |
| `CardLedgerPostingFailing` | any `FAILED` ledger posting in 30m | warning |
| `CardTokenReadsServedFromMirror` | more than half of token reads in 30m came from `LOCAL_MIRROR` | warning |
| `CardDisputesNotReachingTheScheme` | any dispute opening refused `SCHEME_UNAVAILABLE` in 6h | warning |
| `CardTokenProvisioningAlwaysRefused` | every provisioning attempt in 1h was refused | warning |

## Runbooks

### Ledger posting FAILED

The clearing is recorded; the books may not be. Look for the log line `ledger posting FAILED|UNKNOWN for authorization …` and the `outcome=FAILED|UNKNOWN` counter. `UNKNOWN` means the answer was unreadable (a body that did not bind, a cut connection): whether the ledger booked the debit is not known. The posting carries the idempotency key `card-clearing:<authorizationId>:h:<base64url(SHA-256(key))>` (see 03 — API), so posting it again cannot double-post.

Each clearing row carries its posting state (`card_clearings.posting_status` `PENDING` / `POSTED` / `FAILED` / `UNKNOWN`, `posting_attempts`, `posting_last_error`, `posting_next_attempt_at`), written in the clearing's own transaction. The scheduled re-driver (`openbank.card-processing.posting-redrive-cron`, default every minute, heartbeat `card-processing-ledger-posting-redrive`) posts every unposted row again with exponential backoff (`posting-backoff-seconds` 60, capped at `posting-backoff-max-seconds` 3600) up to `posting-max-attempts` (12). An acquirer retry of the same clearing still does **not** touch the ledger; only the re-driver does.

After the last attempt the row stays `FAILED`/`UNKNOWN` (never dropped) and `openbank.card.processing.ledger.posting.exhausted` goes above zero: alert `CardLedgerPostingExhausted`. A row is only ever retried while it is not `POSTED`, and an outcome is only recorded by the attempt that still holds the row's claim token, so a slow superseded attempt can neither overwrite a newer result nor count. (The claim fences recording, not a slow HTTP request after lease expiry. Provider V22 serializes the same key across booking dates; it must be verified before enabling this re-drive.)

`posting_last_error` stores only controlled diagnostics: `POSTING_FAILED`, `COMPLETION_UNPROVEN`, `AUTHORISATION_NOT_FOUND` or `REDRIVE_FAILED`; a successful posting clears it. It does not retain upstream response bodies or exception messages. Posting diagnostics in logs retain opaque clearing/authorisation IDs for correlation; those identifiers remain linkable and are not anonymised. The existing disabled-adapter warning still includes amount, currency and authorisation ID. The shared TransactionClient response-close logger is outside this application-logger proof and can still log a throwable on close failure.

**Remedy for exhausted rows.** Never reset blindly - first prove transaction-service has no booking.

1. In the card-processing database, list the rows and their ledger keys:

```sql
SELECT id, authorization_id, idempotency_key, posting_status, posting_attempts, posting_last_error,
       'card-clearing:' || authorization_id || ':h:' ||
       translate(rtrim(encode(sha256(convert_to(idempotency_key, 'UTF8')), 'base64'), '='), '+/', '-_') AS ledger_key
FROM card_clearings
WHERE posting_status <> 'POSTED' AND posting_attempts >= 12;
```

2. In the transaction-service database, look each `ledger_key` up:

```sql
SELECT idempotency_key, id, status FROM transactions WHERE idempotency_key IN ('<ledger_key>', ...);
-- V22 registry: a retained/orphan claim also prevents automatic retry.
SELECT idempotency_key, transaction_id, booking_date
FROM transaction_idempotency_keys WHERE idempotency_key IN ('<ledger_key>', ...);
```

   - `COMPLETED`: the debit IS booked; mark the card row done: `UPDATE card_clearings SET posting_status = 'POSTED', posting_transaction_id = '<id>', posting_claim_token = NULL, posting_last_attempt_at = now() WHERE id = '<clearing id>';`
   - no transaction row and no registry claim: after confirming no write/retry is still in flight, an owner may approve re-drive: `UPDATE card_clearings SET posting_attempts = 0, posting_next_attempt_at = now(), posting_claim_token = NULL WHERE id IN ('<clearing id>', ...) AND posting_status <> 'POSTED';`
   - `FAILED` / `REVERSED` / `PENDING` / `PROCESSING`: transaction-service holds the key in that state and will keep answering with it; this is a transaction-service incident, not a reset - do not reset.

**One-off reconciliation of clearings from before V5.** V5 marked every existing row `POSTED` (their state was never recorded). Those rows are recognisable by `posting_last_attempt_at IS NULL`. Run step 1 below in the card-processing database, step 2 in the transaction-service database; step 2 returns only keys absent from both transaction history and the global idempotency registry (transaction-service V22 is required). After confirming no writer or retry is still in flight, these rows may be approved for re-drive (set `posting_status = 'UNKNOWN'`, `posting_attempts = 0`, `posting_next_attempt_at = now()` on that row, `posting_claim_token = NULL`).

```sql
-- 1. card-processing DB
SELECT 'card-clearing:' || authorization_id || ':h:' ||
       translate(rtrim(encode(sha256(convert_to(idempotency_key, 'UTF8')), 'base64'), '='), '+/', '-_') AS ledger_key
FROM card_clearings WHERE posting_status = 'POSTED' AND posting_last_attempt_at IS NULL;
-- 2. transaction-service DB: paste the keys from step 1
SELECT k.ledger_key
FROM (VALUES ('<ledger_key>'), ...) AS k(ledger_key)
WHERE NOT EXISTS (
    SELECT 1 FROM transactions t WHERE t.idempotency_key = k.ledger_key
)
AND NOT EXISTS (
    SELECT 1 FROM transaction_idempotency_keys r WHERE r.idempotency_key = k.ledger_key
);
```

Keys already held by a `FAILED`, `REVERSED`, `PENDING` or `PROCESSING` transaction are excluded from this retry list and require the incident triage above; do not reset them or invent a new key. A registry claim without its transaction is also an incident requiring reconciliation, never proof that no booking occurred.

### Every authorisation is declined

`CardIssuanceAdapter` fails closed. Check card-issuance health and the outbound OIDC client before looking at limits. Compare the `reason` tag on `openbank.card.processing.authorizations`.

### Holds are not being released

Check the `card-processing-hold-expiry` liveness gauge and logs for `card hold expiry sweep failed`. Count due holds:
`SELECT count(*) FROM card_authorizations WHERE status IN ('APPROVED','PARTIALLY_CLEARED') AND expires_at < now();`

### Outbox lag or dead letters

`SELECT status, count(*) FROM card_outbox GROUP BY status;` then inspect `last_error` for `FAILED`/`DEAD` rows. Check Kafka reachability; the publisher is circuit-broken.

### A BIN lookup answers NOT_BOUND

Expected when the binding is `visa`/`mastercard` and the credentials are absent — the adapter makes no request. For tokenisation and disputes `NOT_BOUND` is the only vendor answer: those programmes need a scheme contract.

### Token reads served from the mirror

`source: LOCAL_MIRROR` means the tokenisation binding did not answer and the list may be stale; `degradedReason` names the failure. With `CARD_SCHEME_TOKENISATION` set to `visa` or `mastercard` this is permanent (`NOT_BOUND` — no vendor adapter exists); switch back to `simulator` or accept the mirror.

### A chargeback could not be opened

`SCHEME_UNAVAILABLE` on `openbank.card.disputes.opened` means no row was written — opening fails closed. Same `NOT_BOUND` cause as above when `CARD_SCHEME_DISPUTE` names a vendor. `NO_NETWORK_REFERENCE` is a data problem on the authorisation (the acquirer sent no reference), not an outage.

### A token request answers 409 IDEMPOTENCY_REQUEST_IN_PROGRESS indefinitely

The key's reservation is stuck `PENDING`: a request failed after the network was asked (logged at ERROR, "idempotency key left PENDING"). It is never released automatically, because the network may have minted the token or opened the case. Check with the scheme what exists, then either complete the row (`state='COMPLETED'`, `result_id`) against the record you create, or delete it if the network did nothing. Stuck keys: `SELECT * FROM card_lifecycle_idempotency WHERE state='PENDING' AND created_at < now() - interval '10 minutes'`.

### A closed dispute disagrees with the network

`openbank.card.dispute.terminal.mismatches` > 0: a refresh of a WON/LOST/WITHDRAWN case found the network reporting another outcome. The stored outcome is kept on purpose; investigate with the scheme (log line "closed dispute … is stored … but the network now reports …").

## Testing & CI

- Unit: `AuthorizationLifecycleTest`, `CardProcessingServiceTest`, `CardIssuanceAdapterTest`, `TransactionLedgerPostingAdapterTest`, `MastercardOAuthSignerTest`, `CardTokenServiceTest`, `CardDisputeServiceTest`, `SchemeAdapterFailureTest`, and one test per simulator.
- Integration: `CardLifecycleIdempotencyIT` (parallel same-key requests behind a latch, card state, evidence history) and `CardAuthorizationOutboxIT` against PostgreSQL (`openbank_card_processing_it`), `HoldExpirySweepVertxContextIT` drives the real cron. Ledger posting and fraud scoring are switched off in `%test`.
- Contract: `CardIssuanceAuthorizationPactConsumerTest` (consumer pact against card-issuance).
- Generated runbook: `docs/runbooks/svc-card-processing.md`.

## Deploy / release

Versioning via `version.txt` and release-please; do not hand-edit. Docker image is a fast-jar.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
