# Operations

## Build & run

```bash
# Build (locally) — fast-jar (never uber-jar)
./gradlew :openbank-interest-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-interest-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

Image build is host-side fast-jar (`-Dquarkus.package.jar.type=fast-jar`); the runtime stage COPYs `quarkus-app/`. Generic build: `openbank-infra/scripts/build-push-service.sh interest-service`.

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/interest/...` | 8125 | business REST API |
| `/api/v1/interest/withholding/remittances/...` | 8125 | withholding remittance API |
| `/api/v1/info` | 8125 | ServiceInfoResource (build + version metadata) |
| `/api/docs` | 8125 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Management endpoints are served on the separate management port **8085** (`quarkus.management.enabled`, root-path `/q`). The app HTTP port is **8125**.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault (ADR-0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **override in prod via Vault** |
| (datasource reactive URL) | `postgresql://localhost:5432/openbank_interest` | DB connection |
| (OIDC auth-server-url) | `http://localhost:8080/realms/openbank` | OIDC issuer |
| (Redis hosts) | `redis://localhost:6379` | Valkey (wired; no idempotency-key flow in v1) |
| (OTEL endpoint) | `http://localhost:4317` | OpenTelemetry OTLP |

Scheduled jobs (config keys under `openbank.interest`):

| Key | Default | Purpose |
|---|---|---|
| `accrual-cron` | `0 0 1 * * ?` | daily accrual tick (01:00) |
| `capitalization-cron` | `0 0 2 1 * ?` | monthly capitalization tick (02:00 on the 1st) |
| `day-count-convention` | `ACT_365` | default day-count when not set on the config |
| `openbank.outbox.poll-interval` | `5s` | outbox dispatcher tick |

The placeholder secrets are dev-only; production must inject real values via Vault (ADR-0017).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC running.
- **Readiness:** `/q/health/ready` (port 8085) — reactive DB pool + Kafka producer.

Flyway runs `migrate-at-start` with `connect-retries: 10` (2s interval) so the pod waits for the DB on cold start.

## FinOps workload tier (ADR-0057)

`interest-service` is **not** a money-path service (not in `rules.yaml: money_path_services`) and is therefore **not T0** by default. Its workload is a mix of:

- **Async outbox dispatcher** (Kafka publisher) — a **T2** (event → 0) candidate (KEDA on consumer-group lag / outbox backlog).
- **Periodic scheduler** (accrual/capitalization cron) — a **T3** (periodic, no listener) shape; the schedule must wake the workload.
- **HTTP read/admin surface** — a **T1** (HTTP → 0) candidate, cold-start tolerant within SLO.

Per ADR-0057 the tier is **derived from measured behaviour**, not hand-assigned; the default for a new non-money-path service is the lowest tier its trigger allows. **Caveat:** the outbox dispatcher pins the Deployment to `replicas: 1` (single-writer guarantee, ADR-0050), and the cron jobs must fire on schedule — both constrain how aggressively the workload can scale to zero. Reconcile the declared tier with the dispatcher/scheduler invariants before flipping scale-to-zero.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.5% (admin/batch surface) | Prometheus `up{service="interest-service"}` |
| Latency p95 GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 capitalize | < 500 ms | includes tax-profile resolve + DB writes + outbox insert |
| Outbox lag | < 30 s | pending-age on `interest_outbox` |
| Error rate | < 0.5% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

(Targets are documentation defaults; confirm against the platform SLO register — TBD.)

## Runbooks

### Outbox lag growing

1. Count pending: `SELECT count(*) FROM interest_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the topic `openbank.interest.accrual.event`.
3. Check dispatcher logs: `kubectl logs -l app=interest-service | grep InterestOutboxDispatcher`.
4. The publisher has a circuit breaker (`failureRatio 0.5`, `delay 5000ms`); repeated failures bound a row toward the DEAD transition (ADR-0050 N5). Inspect `last_error` / `attempt_count`.

### Capitalization fails with "No active rate config"

Cause: no `interest_rate_configs` row active for the product on `toDate`. Action: create/activate a rate config via `POST /api/v1/interest/rates` covering the period.

### Withholding looks wrong (tax 0 when expected, or vice versa)

1. Confirm the interest currency — only **CZK** is withheld in v1; non-CZK is `DEFERRED_FX` (tax 0).
2. Confirm the beneficiary profile — v1 always resolves to the fail-safe CZ-resident-individual default (15 %); legal-entity / treaty / exempt paths require the party tax resolution fast-follow.
3. Inspect the `withholding_tax` row: `treatment`, `rate`, `taxable_base`, `tax_amount`.

### Remittance assembled but cash not moved

By design: `interest-service` moves no cash. The `interest.withholding.remitted.v1` event delegates the odvod to the downstream tax/reporting consumer, which flips the batch to `SETTLED`. Check the consumer, not this service.

## Tech-stack matrix

Auto-surfaced in `/api/v1/info` (from `libs.versions.toml`):

| Component | Version |
|---|---|
| Kotlin | 2.x (platform pin) |
| Quarkus | 3.x LTS |
| JDK runtime | per platform (Temurin) |
| PostgreSQL | 16 (reactive `pg-client` + JDBC for Flyway) |
| Kafka client | SmallRye Reactive Messaging |

## Deploy / release

- Per-service path-scoped CI: test (unit + Testcontainers PostgreSQL/Valkey per JVM, in-memory Kafka), `quarkusBuild` fast-jar, SBOM, image build/push, ArgoCD picks up the tag.
- **Release axis** (release-please) is driven by `version.txt` + Conventional Commits; **API-contract axis** is `openapi.yaml: info.version` (ADR-0048). Do not force them equal.
- GitOps: for image-tag merge conflicts take `--ours` (the freshly-built tag); for RBAC/config take `--theirs` or resolve manually.
