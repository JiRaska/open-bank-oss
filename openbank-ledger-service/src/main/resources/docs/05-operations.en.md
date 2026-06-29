# Operations

## Build & run

```bash
# Build (locally, host-side fast-jar — never uber-jar)
./gradlew :openbank-ledger-service:quarkusBuild

# Run dev mode (live reload; OIDC + scheduler behaviour adjusted via %dev)
./gradlew :openbank-ledger-service:quarkusDev

# Local gate before a PR
./gradlew :openbank-ledger-service:detekt ktlintCheck koverVerify build

# Image build/push (host-side build first)
openbank-infra/scripts/build-push-service.sh ledger-service
```

The build uses the `openbank.quarkus-service` convention plugin (ADR-0049 D1). Kover floor is enforced at **40%** lines (`koverVerify` wired into `check`); money-path target is 70%. Provider-side Pact verification runs against the shared `pacts/` dir (ADR-0063).

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/journals/...` | 8101 | business REST API (ledger) |
| `/api/v1/ledger/fx-revaluation` | 8101 | FX revaluation ops trigger |
| `/api/v1/info` | 8101 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/swagger-ui` | 8085 | Swagger UI (dev only) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management endpoints live on the dedicated **management port 8085** (`quarkus.management.root-path: /q`); the business API is on **8101**.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` / `..._JDBC_URL` | `…localhost:5432/openbank_ledger` | reactive (app) + JDBC (Flyway) datasource |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | (cluster) | Kafka brokers for the outbox topic |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `FX_SERVICE_URL` | `http://localhost:8119` | fx-service REST base (ČNB rates) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata for `/api/v1/info` |
| `openbank.ledger.partition.*` | see below | partition lifecycle knobs |

Partition knobs (`application.yaml`): `future-years: 2`, `retention-years: 10`, `drop-enabled: false`, `dry-run: true`. Flipping `drop-enabled`/`dry-run` is a deliberate, archived operator action — DROP is physically destructive.

Security response headers (CSP, HSTS, X-Frame-Options DENY, nosniff, etc.) are set in `application.yaml`. Rate limiting: `openbank.rate-limit` (max 100 concurrent requests).

## Serverless tier (ADR-0057)

ledger-service is a **money-path / T0** service. T0 is the highest-availability tier and is **not** scaled to zero — it is always-on with `replicas: 1` (single-writer outbox invariant, ADR-0050 N4). The scale-to-zero classifier explicitly excludes money-path services; T0 demotion would require an ADR-0030 threat model + 2 approvals.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — reactive PG connection + Kafka producer.

Flyway is configured with `connect-retries: 10` / `2s` interval so the pod tolerates a slow DB at startup.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.95% (money-path / T0) | `up{service="ledger-service"}` |
| Latency p95 GET (journals/trial-balance) | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (post journal) | < 300 ms | includes balance validation + outbox insert |
| Outbox lag | < 10 s (dispatch tick = 5 s) | pending-age of `ledger_outbox` |
| Trial balance | nets to zero, always | `GET /journals/trial-balance` `balanced=true` |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing

1. Count PENDING: `SELECT count(*) FROM ledger_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the dispatcher tick logs: `kubectl logs -l app=ledger-service | grep LedgerOutboxDispatcher`.
3. Single-writer invariant: ledger Deployment **must** stay `replicas: 1`. Do NOT scale out to clear a backlog — that breaks the in-JVM `SKIP` single-claim guarantee (`FOR UPDATE SKIP LOCKED` is the tracked multi-writer refinement).
4. Repeated per-row failures move a row toward DEAD (bounded, ADR-0050 N5); inspect `last_error` / `attempt_count`.

### Trial balance does not net to zero

Treat as a P1 accounting-integrity incident. Per-currency balancing (ADR-0025) is enforced at post time, so a non-zero trial balance points to data corruption or a partition-routing miss. Freeze postings, snapshot the journal, and reconcile against `balance-service` before any correcting entry. Corrections are reversal-only — never edit a posted entry.

### FX revaluation missed / re-run

The daily run is automated by `FxRevaluationScheduler`. To re-run for a business day: `POST /api/v1/ledger/fx-revaluation?date=YYYY-MM-DD` (operator). It is idempotent (`fx-reval-{date}`) — one entry per day, same-day re-run is a no-op. A missing ČNB rate for a currency skips only that leg (logged WARN).

### Partition horizon / retention

`JournalPartitionMaintainer` rolls forward automatically. Verify partitions: `\d+ journal_entries`. Lifecycle actions are in `partition_lifecycle_audit`. A non-empty `journal_entries_default` (DEFAULT_NONEMPTY audit row) means roll-forward fell behind — investigate before it blocks new partition ATTACH.

## Tech-stack version matrix

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Jackson | 2.17.2 |
| Hibernate Reactive (Panache) | via Quarkus BOM |
| SmallRye Reactive Messaging (Kafka) | via Quarkus BOM |

## Deploy / release

Per-service path-scoped CI (only changed services build). Release axis is owned by **release-please** from Conventional Commits — do not hand-edit `version.txt`/`CHANGELOG.md` (current release `1.2.0`). CD: ArgoCD tracks the deployment manifest image tag. Always take `--ours` for image-tag merge conflicts in GitOps.
