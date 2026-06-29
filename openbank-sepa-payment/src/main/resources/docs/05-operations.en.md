# Operations

## Build & run

```bash
# Build (locally) — fast-jar (never uber-jar)
./gradlew :openbank-sepa-payment:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-sepa-payment:quarkusDev

# Local gate before a PR
./gradlew :openbank-sepa-payment:detekt ktlintCheck koverVerify build

# Generic image build (host-side gradle, then Docker COPY of quarkus-app/)
openbank-infra/scripts/build-push-service.sh sepa-payment
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/sepa-payments/...` | 8115 | business REST API |
| `/api/v1/info` | 8115 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8115 | Swagger UI (`quarkus.swagger-ui.path`) |
| `/q/openapi` | 8085 | OpenAPI spec (management) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> The management interface is enabled on a separate port `8085` with root-path `/q` (`quarkus.management.*`).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sanctions-service REST client (screening gate) |
| `AML_SERVICE_URL` | `http://localhost:8117` | aml-service REST client (case opening) |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory→enforce switch |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata for `/api/v1/info` |

The DB is reached via both reactive (`postgresql://…/openbank_sepa_payments`) and JDBC (Flyway) URLs. Redis at `redis://localhost:6379` backs idempotency.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB pool + Kafka producer + Redis.

Note for the **screening gate**: the sanctions/AML REST clients are wrapped in MicroProfile Fault Tolerance (circuit breaker, retry, timeout — see `openbank.resilience.*`). A sanctions outage does **not** fail readiness; it makes `createPayment` fail-closed (payment held in `RECEIVED`).

## FinOps tier (ADR-0057)

**T0 — Always-on.** As a money-path synchronous hop, sepa-payment must not eat a cold-start, so `minReplicas ≥ 1`; it never scales to zero. The tier is derived from measured behaviour and gated declared-vs-measured in CI.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="openbank-sepa-payment"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create incl. sync screen) | < 800 ms | includes DB write + 2× sanctions screen call |
| Outbox lag | < 10 s | age of oldest PENDING `sepa_payment_outbox` row |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Payments stuck in RECEIVED

This is **by design** when screening returns REVIEW or is unavailable (fail-closed, ADR-0032).
1. Check whether the sanctions service is healthy: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. List held payments: `SELECT payment_id, status, created_at FROM sepa_payments WHERE status='RECEIVED' ORDER BY created_at`.
3. Check the matching AML cases in aml-service (alert codes `AML_HOLD` / `SCREENING_UNAVAILABLE`).
4. A held payment is released or rejected through the AML case lifecycle / a `PATCH …/status` transition, never automatically.

### Outbox lag growing

1. `SELECT count(*) FROM sepa_payment_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the dispatcher logs (`SepaPaymentOutboxDispatcher`).
3. Inspect `last_error` / `attempt_count` on FAILED rows; the circuit breaker may be open after repeated publish failures.

### Idempotency replay

A repeated `POST` with the same `Idempotency-Key` returns the cached response with `X-Idempotency-Replayed: true`. A blank key returns `400`. Do not "fix" by reusing a key with a different body — generate a new key per logical payment.

### DB / migrations

Never modify an applied migration (checksum mismatch → startup fail). For a settled live DB use `QUARKUS_FLYWAY_REPAIR_AT_START=true`, then remove it.

## Tech-stack version matrix

Resolved from `libs.versions.toml` at build, surfaced in `/api/v1/info`:

| Component | Version |
|---|---|
| Kotlin | 2.x |
| Quarkus | 3.x LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate | Reactive (Panache) |

> Exact pinned versions are owned by `openbank-libs` `libs.versions.toml`; this service inherits them via the `openbank.quarkus-service` convention plugin.

## Deploy / release

Per-service CI pipeline + release-please (per-service component, `version.txt`). As a **money-path** service, a merge needs **2 approvals + an up-to-date threat model**. Image builds are fast-jar, host-side gradle then Docker COPY of `quarkus-app/`. CD via ArgoCD picks up the new image tag from the GitOps manifest.
