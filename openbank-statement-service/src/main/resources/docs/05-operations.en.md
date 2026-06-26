# Operations

## Build & test

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew :openbank-statement-service:build                 # one service
./gradlew :openbank-statement-service:test --offline        # tests (Testcontainers PG per JVM)
./gradlew detekt ktlintCheck koverVerify build              # local pre-PR gate
```

- The runtime stack is reactive (`io.smallrye.mutiny.Uni`), not Kotlin suspend.
- Tests use an isolated PostgreSQL per test JVM via Testcontainers (CI infra sweep #578); Kafka is in-memory (`smallrye-reactive-messaging-in-memory`). Local `*ApiIT`/`%test` profile hits `localhost:5432` database `openbank_statement_it`.
- The `%test` profile disables the scheduler (`quarkus.scheduler.enabled=false`) so the integration test drives `dispatchScheduledBatch()` explicitly and never races assertions (ADR-0050).

## Image & deploy

- **Always fast-jar, never uber-jar** — the Dockerfile uses `-Dquarkus.package.jar.type=fast-jar` and the runtime stage COPYs `quarkus-app/`. Generic build: `openbank-infra/scripts/build-push-service.sh openbank-statement-service` (host-side `quarkusBuild` first, never in-Docker Gradle).
- Deployed via GitOps (ArgoCD). The Deployment is pinned to **`replicas: 1`** — this is load-bearing for the single-writer outbox guarantee (ADR-0050 N4).
- **Serverless tier (ADR-0057):** statement-service is a batch/on-demand workload (monthly cron + interactive renders), a natural candidate for a scale-to-zero / scale-down tier rather than always-hot. Confirm the exact tier in the FinOps classifier; not a latency-critical money-path service.

## Configuration (env)

| Var | Purpose | Default |
|---|---|---|
| `POSTGRES_PASSWORD` | DB password | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OIDC_CLIENT_SECRET` | OIDC inbound + M2M client secret | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | Keycloak realm URL | `http://localhost:8080/realms/openbank` |
| `TRANSACTION_SERVICE_URL` | booked-entry reads | `http://localhost:8102` |
| `BALANCE_SERVICE_URL` | reconciliation closing balance | `http://localhost:8105` |
| `ACCOUNT_SERVICE_URL` | pocket account info | `http://localhost:8100` |
| `PARTY_SERVICE_URL` | holder name | `http://localhost:8111` |
| `openbank.statement.close-cron` | monthly close cron | `0 30 2 1 * ?` |
| `openbank.statement.scheduled-close.enabled` | enable the cron | `true` (app), default-false in code |
| `openbank.outbox.poll-interval` / `initial-delay` | outbox dispatch tick | `5s` / `5s` |

## Ports & probes

- **App:** 8136. **Management:** 8085, root-path `/q` (health, metrics, docs).
- **Health:** SmallRye Health (`quarkus-smallrye-health`) at `/q/health` (`/live`, `/ready`).
- **Metrics:** Micrometer → Prometheus at `/q/metrics`; close-cadence counters via `CloseMetricsAdapter`.
- **Tracing:** OpenTelemetry OTLP → `http://localhost:4317` (configurable), `service.name=openbank-statement-service`.
- **Docs:** Docs-as-Service at `/q/openbank/docs` (ADR-0019).
- **Security headers** set globally (CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, etc.).

## SLO (proposed)

| Indicator | Target |
|---|---|
| Render (camt.053/MT940/PDF) latency p99 | < 2 s (on-demand, includes upstream entry replay) |
| Period-close success rate (clean reconcile) | ≥ 99.9% of pockets per cadence |
| Outbox dispatch lag | < 30 s from close to Kafka |
| Monthly close cadence | runs on the 1st 02:30; missed runs self-heal next pass |

## Runbooks

### Period-close fails with 409 (reconciliation mismatch)
The computed closing (`opening ± booked net movement`) disagreed with balance-service's reported closing. This is **by design** — no inconsistent statement is issued. Investigate: (1) compare the close error `delta`; (2) check for un-booked or late transactions in transaction-service for the period; (3) confirm balance-service's closing for the pocket/date; (4) once the source data agrees, re-run the close (idempotent). The failure is recorded in `statement_close_failure` with `reason=RECONCILIATION`.

### Scheduled close did not run / left pockets owed
Check `GET /api/v1/statements/close-runs/latest` and `/close-runs`. The cadence is self-healing — the next pass closes every month still owed through the prior month. To force it now: `POST /api/v1/statements/close-runs` (operator). Inspect per-pocket failures via `/close-runs/{runId}/failures`.

### Outbox row stuck / DEAD
Repeated publish failures increment `attempt_count`; at 10 the row is parked DEAD (excluded from dispatch) and a WARN is logged (`statement.outbox.dead`). Investigate Kafka connectivity/topic; once resolved, a DEAD row requires manual intervention to re-queue (it is intentionally not auto-retried).

### Account registry empty / scheduled close enumerates nothing
The registry back-fills from the `openbank.accounts.account.created` topic with `auto.offset.reset=earliest` on first deploy. If empty, verify the consumer group `openbank-statement-service` is consuming and account-service is emitting; re-set the consumer offset to earliest if a re-back-fill is needed.

### Flyway checksum mismatch on startup
Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the GitOps env, let it settle, then remove. Never rewrite an applied migration.
