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
| `openbank.card.processing.ledger.postings` | `outcome` (`POSTED` / `SKIPPED_DISABLED` / `FAILED`) |
| `openbank.card.processing.fraud.scores` | `outcome` (`SCORED` / `SKIPPED_DISABLED` / `FAILED`) |

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

The clearing is recorded; the books are not. Look for the log line `ledger posting FAILED for authorization …` and the `outcome=FAILED` counter. Check transaction-service reachability and the client-credentials token. The posting carries the idempotency key `card-clearing:<authorizationId>:h:<base64url(SHA-256(key))>` (see 03 — API), so a replay through transaction-service cannot double-post. An acquirer retry of the same clearing does **not** re-attempt the posting: the clearing key is already applied and replays without touching the ledger, so a `FAILED` posting must be re-driven deliberately.

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

- Unit: `AuthorizationLifecycleTest`, `CardProcessingServiceTest`, `CardIssuanceAdapterTest`, `TransactionLedgerPostingAdapterTest`, `FraudScoringAdapterTest`, `MastercardOAuthSignerTest`, `CardTokenServiceTest`, `CardDisputeServiceTest`, `SchemeAdapterFailureTest`, and one test per simulator.
- Integration: `CardLifecycleIdempotencyIT` (parallel same-key requests behind a latch, card state, evidence history) and `CardAuthorizationOutboxIT` against PostgreSQL (`openbank_card_processing_it`), `HoldExpirySweepVertxContextIT` drives the real cron. Ledger posting and fraud scoring are switched off in `%test`.
- Contract: `CardIssuanceAuthorizationPactConsumerTest`, `CardClearingTransactionPactConsumerTest`, and `CardAuthorizationFraudPactConsumerTest`. The transaction and fraud providers replay both the success and unauthenticated 401 interactions from committed pacts. Fraud scoring sends a major-unit amount with `currency` and `rail = CARD`, and reads the returned `verdict`.
- Generated runbook: `docs/runbooks/svc-card-processing.md`.

## Deploy / release

Versioning via `version.txt` and release-please; do not hand-edit. Docker image is a fast-jar.
