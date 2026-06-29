# Operations

## Build & run

```bash
# Build (locally, fast-jar)
./gradlew :openbank-consent-service:quarkusBuild

# Run dev mode (live reload, OIDC disabled)
./gradlew :openbank-consent-service:quarkusDev

# Docker (multi-stage, fast-jar; see Dockerfile)
docker build -t openbank/consent-service -f openbank-consent-service/Dockerfile .
```

Runtime entrypoint uses ZGC: `java -XX:+UseZGC -jar /app/quarkus-run.jar` on Eclipse Temurin 25 JRE (alpine), non-root user.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/consents/...` | 8106 | business REST API |
| `/api/v1/info` | 8106 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8106 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (management port) |

The management interface is enabled on a **separate port 8085** (`quarkus.management.port`, root-path `/q`); the business API is on 8106.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | OIDC client secret — **override in prod** |
| `SCA_SERVICE_URL` | `http://localhost:8110` | sca-service base URL (REST client) |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA decision path |
| `AUTHZ_ENFORCE` | `false` | flip to `true` to enforce OPA decisions (ADR 0034) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build provenance surfaced in `/api/v1/info` |

DB (reactive): `postgresql://localhost:5432/openbank_consents`; Kafka `localhost:29092`; Redis `redis://localhost:6379`. Rate limiting is on (`openbank.rate-limit.max-concurrent-requests=150`).

## Serverless tier (ADR 0057)

consent-service is on the **request-driven critical path** (validate is called inline before serving Open-Banking data), so it is **not** a scale-to-zero candidate — cold-start latency on a validate call would violate the latency SLO and stall TPP traffic. It runs as an always-on deployment with a warm minimum replica count. (If a tiering table is published in `openbank-infra`, consent-service is classified accordingly there.)

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC running.
- **Readiness:** `/q/health/ready` (port 8085) — DB pool + Kafka producer + Redis reachable.

SmallRye Health is mounted at `/q/health`. Probes should target the management port 8085.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="openbank-consent-service"}` |
| Latency p95 `validate` | < 50 ms | `http_server_requests_seconds{uri=~".*/validate",quantile=0.95}` |
| Latency p95 `POST /consents` | < 300 ms | includes DB write |
| Latency p95 `activate` | < 500 ms | includes synchronous SCA verification round-trip |
| Outbox lag | < 10 s | dispatcher runs every 5 s, batch 25 |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing

1. Count pending: `SELECT count(*) FROM consent_outbox WHERE status='PENDING';`
2. Inspect failures: `SELECT event_id, attempt_count, last_error FROM consent_outbox WHERE last_error IS NOT NULL ORDER BY updated_at DESC LIMIT 20;`
3. Check Kafka reachability and dispatcher logs (`ConsentOutboxDispatcher`).
4. The dispatcher circuit-breaker may be open — confirm broker health, then it self-recovers on the next 5 s tick.

### Activation returns 503

Symptom: `POST /{id}/activate` → `503 SERVICE_UNAVAILABLE`. Cause: sca-service unreachable after retries / open circuit breaker. Action: check `SCA_SERVICE_URL`, sca-service health, and network policy; activation is safe to retry once SCA is back (the consent stays `PENDING_SCA`).

### Activation returns 422 SCA not completed/mismatch

The referenced challenge is not `COMPLETED`, or its `partyId`/`purpose` (`CONSENT_GRANT`) does not match. This is expected when the customer has not finished SCA — not an incident. No auto-approve fallback exists (ADR 0021).

### Flyway checksum mismatch on startup

Cause: an applied migration was edited. Temporary fix: set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let it settle, then remove. Never rewrite an applied migration — add a new `V{n}`.

## Tech-stack version matrix

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate Reactive (Panache) | via Quarkus BOM |

## Deploy / release

- **Versioning:** per-service SemVer in `version.txt` (currently `0.2.0`), owned by **release-please** — feature/fix PRs do **not** hand-edit it (CLAUDE.md #3).
- **API contract version:** `openapi.yaml info.version` (`1.0.0`) is a separate axis (ADR 0048).
- **Money-path:** every change needs 2 approvals + an up-to-date threat model (`docs/threat-models/openbank-consent-service.md`, ADR 0030) and is never auto-merged.
- **CD:** image built host-side (fast-jar) and rolled out via ArgoCD from the gitops manifests.
