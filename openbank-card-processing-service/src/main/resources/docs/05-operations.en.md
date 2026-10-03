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
| `openbank.card.processing.clearing.conflicts` | — (clearings that lost a concurrent-clearing race and were re-evaluated) |
| `openbank.card.processing.hold.releases` | `kind` (`REVERSAL` / `EXPIRY`) |
| `openbank.card.processing.ledger.postings` | `outcome` (`POSTED` / `SKIPPED_DISABLED` / `FAILED`) |
| `openbank.card.processing.fraud.scores` | `outcome` (`SCORED` / `SKIPPED_DISABLED` / `FAILED`) |

All carry `service="card-processing"`, as do the outbox backlog and dead-letter gauges.

## Runbooks

### Ledger posting FAILED

The clearing is recorded; the books are not. Look for the log line `ledger posting FAILED for authorization …` and the `outcome=FAILED` counter. Check transaction-service reachability and the client-credentials token. The posting carries the idempotency key `card-clearing:<authorizationId>:<key>` (see 03 — API for the long-key digest), so a replay through transaction-service cannot double-post. An acquirer retry of the same clearing does **not** re-attempt the posting: the clearing key is already applied and replays without touching the ledger, so a `FAILED` posting must be re-driven deliberately.

### Every authorisation is declined

`CardIssuanceAdapter` fails closed. Check card-issuance health and the outbound OIDC client before looking at limits. Compare the `reason` tag on `openbank.card.processing.authorizations`.

### Holds are not being released

Check the `card-processing-hold-expiry` liveness gauge and logs for `card hold expiry sweep failed`. Count due holds:
`SELECT count(*) FROM card_authorizations WHERE status IN ('APPROVED','PARTIALLY_CLEARED') AND expires_at < now();`

### Outbox lag or dead letters

`SELECT status, count(*) FROM card_outbox GROUP BY status;` then inspect `last_error` for `FAILED`/`DEAD` rows. Check Kafka reachability; the publisher is circuit-broken.

### A BIN lookup answers NOT_BOUND

Expected when the binding is `visa`/`mastercard` and the credentials are absent — the adapter makes no request. For tokenisation and disputes `NOT_BOUND` is the only vendor answer: those programmes need a scheme contract.

## Testing & CI

- Unit: `AuthorizationLifecycleTest`, `CardProcessingServiceTest`, `CardIssuanceAdapterTest`, `TransactionLedgerPostingAdapterTest`, `MastercardOAuthSignerTest`, `SchemeAdapterFailureTest`, and one test per simulator.
- Integration: `CardAuthorizationOutboxIT` against PostgreSQL (`openbank_card_processing_it`), `HoldExpirySweepVertxContextIT` drives the real cron. Ledger posting and fraud scoring are switched off in `%test`.
- Contract: `CardIssuanceAuthorizationPactConsumerTest` (consumer pact against card-issuance).
- Generated runbook: `docs/runbooks/svc-card-processing.md`.

## Deploy / release

Versioning via `version.txt` and release-please; do not hand-edit. Docker image is a fast-jar.
