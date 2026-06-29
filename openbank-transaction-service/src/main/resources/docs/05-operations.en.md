# Operations

## Build

```
./gradlew :openbank-transaction-service:build       # compile + test
./gradlew detekt ktlintCheck koverVerify build      # local gate before a PR
```

CI is path-scoped (only changed services build). The domain layer has **zero** framework imports. Coverage gate (Kover): line coverage ≥ 40 (`build.gradle.kts`); ratchet-only, never lower. Money-path services aim higher.

### Image

- **fast-jar only** — the Dockerfile COPYs `quarkus-app/`. Never `uber-jar` (leaves `quarkus-app/` empty → crashloop).
- **Host-side build, not in-Docker Gradle.** Generic build: `openbank-infra/scripts/build-push-service.sh transaction-service`.

## Configuration (key env)

| Env | Purpose | Default (dev) |
|---|---|---|
| `POSTGRES_PASSWORD` | DB credential | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OIDC_CLIENT_SECRET` | Keycloak client secret (OIDC + oidc-client) | placeholder |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | realm URL | `http://localhost:8080/realms/openbank` |
| `LEDGER_SERVICE_URL` | ledger REST client | `http://localhost:8101` |
| `BALANCE_SERVICE_URL` | balance REST client | `http://localhost:8103` |
| `FX_SERVICE_URL` | fx REST client | `http://localhost:8119` |
| `BUILD_TIME` / `GIT_COMMIT` | build metadata for `/api/v1/info` | `unknown` |

Ports: **8102** app, **8085** management (`/q`). Rate limit: `openbank.rate-limit.enabled=true`, `max-concurrent-requests=150`. Outbox poll: every 5 s, initial delay 5 s.

## Health probes

SmallRye Health is on the management interface (`/q`, port 8085):

- **Liveness:** `/q/health/live`
- **Readiness:** `/q/health/ready` (DB connectivity)
- **Metrics:** `/q/metrics` (Micrometer / Prometheus)
- **Docs:** `/q/openbank/docs` (this documentation)

OpenTelemetry traces export to OTLP `http://localhost:4317` (`service.name = openbank-transaction-service`).

## Serverless tier (ADR-0057)

Transaction-service is a **money-path** service, therefore **T0** by the `t0_baseline: money_path_services` rule (`rules.yaml`). T0 = always-warm, `min > 0` replicas. It is **not** a scale-to-zero candidate: it sits on the synchronous payment path (a cold start would add to a chain of sync ledger/balance calls). Demoting it below T0 requires an ADR-0030 threat-model update + 2 approvals.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target (proposed) | Notes |
|---|---|---|
| Availability | 99.9% | money-path |
| Initiate p95 latency | TBD | dominated by synchronous ledger + balance + (optional) FX calls |
| Outbox publish lag | < 10 s | dispatcher runs every 5 s |
| RTO / RPO | 15 min / 5 min | inherited platform target (see account-service pilot) |

Exact latency SLO numbers are TBD until measured in the sandbox; the saga's downstream calls have 2 s connect / 3 s read timeouts (`application.yaml`).

## Runbooks

### Outbox stuck (events not publishing)
1. Check `transaction_outbox` for rows with `status='FAILED'` and `last_error`.
2. The dispatcher wraps publish in a `@CircuitBreaker` — a tripped breaker pauses publishing; inspect logs for the breaker state and Kafka connectivity.
3. After Kafka recovers, FAILED rows are retried on the next poll (re-listed by `listProcessable`).

### Saga stuck in-flight
1. Query `payment_sagas WHERE state NOT IN ('COMPLETED','COMPENSATED','FAILED')` (covered by the partial index).
2. A saga that threw mid-flight should already be COMPENSATED/FAILED (compensation is best-effort and idempotent). A row left in FUNDS_RESERVED indicates a hold was placed — balance-service expires it after the 300 s TTL, so no funds leak.
3. Verify ledger consistency: a posted journal is reversed during compensation; a captured debit is refunded (`compensation-{txId}`).

### Flyway checksum mismatch on startup
- Never rewrite an applied migration. If a checksum mismatch blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let the DB settle, then remove it.

### FX rate unavailable
- Cross-currency initiate fails with `FxRateUnavailableException` when fx-service has no quote. Confirm `FX_SERVICE_URL` and fx-service health; same-currency settlements are unaffected (no FX leg).

## Deploy

GitOps via ArgoCD (sandbox tracks the deploy branch). For image-tag merge conflicts in gitops manifests, take `--ours` (the freshly-built tag), never blind `--theirs`. Verified-signature ruleset requires the commit email match the registered GPG key.
