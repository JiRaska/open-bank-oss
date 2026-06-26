# Operations

## Build & run

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

# Unit + integration tests
./gradlew :openbank-sdd-service:test

# Fast-jar build (never uber-jar — see CLAUDE.md GitOps rules)
./gradlew :openbank-sdd-service:quarkusBuild

# Dev mode (live reload; OIDC + scheduler tuned per %dev profile)
./gradlew :openbank-sdd-service:quarkusDev
```

The runtime is reactive (`io.smallrye.mutiny.Uni`), not Kotlin `suspend`.

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/sdd/...` | 8129 | business REST API |
| `/api/docs` | 8129 | Swagger UI |
| `/api/v1/info` | 8129 | ServiceInfoResource (build metadata) — from `openbank-libs` |
| `/q/openbank/docs` | 8086 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8129 | OpenAPI spec |
| `/q/health` | 8086 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8086 | Prometheus (Micrometer) |

The **management interface** is enabled on a separate port: `management.port: 8086`, `root-path: /q`. The app HTTP port is **8129**.

## Configuration

| Setting | Default | Purpose |
|---|---|---|
| `quarkus.http.port` | `8129` | app port |
| `quarkus.management.port` | `8086` | management (health/metrics/docs) port |
| datasource reactive URL | `postgresql://localhost:5432/openbank_sdd` | PostgreSQL |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod (Vault, ADR-0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | OIDC client secret — **MUST be overridden in prod** |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | Keycloak issuer |
| Kafka topic (`sdd-events-out`) | `openbank.sdd.event` | outbox publish target |
| `openbank.outbox.poll-interval` | `5s` | dispatcher tick (also hard-coded `@Scheduled(every = "5s")`) |
| `openbank.sdd.expiry-cron` | `0 15 3 * * ?` | idle-expiry sweep schedule |
| `openbank.sdd.expiry.enabled` | `false` | **idle-expiry sweep is OFF by default** |

The placeholders are dev-only; production must inject real secrets via Vault (ADR-0017). Logs are JSON in the default profile.

## Workload tier (ADR-0057)

`openbank-sdd-service` is **not** a money-path service and is **not** a regulator-mandated always-on service, so it does not require the T0 (always-on) floor. It is a synchronous request/response HTTP service with a transactional-outbox dispatcher:

- The outbox dispatcher is a **single writer** — `concurrentExecution = SKIP` plus the Deployment pinned to **`replicas: 1`** (ADR-0050 N4). Any scale-to-zero / autoscaling decision must preserve the single-writer guarantee (the `FOR UPDATE SKIP LOCKED` claim is the tracked refinement before multi-writer is allowed).
- Per ADR-0057 the **default for a new service is the lowest tier its trigger allows**; the exact tier is derived by the FinOps classifier from measured traffic, not hand-assigned here. (TBD — confirm the assigned tier in the gitops/classifier output.)

## Health probes

- **Liveness:** `/q/health/live` — JVM + ArC running.
- **Readiness:** `/q/health/ready` — datasource connectivity + Kafka producer.

`scheduler.enabled` is false under `%test` so the integration test drives the outbox dispatch explicitly and never races assertions (ADR-0050).

## SLO (targets)

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="openbank-sdd-service"}` |
| Latency p95 (authorise / lifecycle) | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Outbox lag | < 10 s | age of the oldest `PENDING`/`FAILED` row |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing

1. Count backlog: `SELECT status, count(*) FROM sdd_outbox GROUP BY status;`
2. Look for `DEAD` rows (poison): `SELECT event_id, event_type, attempt_count, last_error FROM sdd_outbox WHERE status='DEAD';` — these are parked at `MAX_ATTEMPTS` (10) and never re-dispatched. Investigate `last_error`; requeue manually only after fixing the cause (set `status='PENDING'`, `attempt_count=0`).
3. Check Kafka reachability and the `sdd-service` dispatcher logs (`grep SddOutboxDispatcher`); the circuit breaker may have opened.
4. Confirm exactly one replica is running (`replicas: 1`) — multiple writers would double-publish.

### Illegal transition (409) reported by a caller

Symptom: `409 Conflict`, body `Illegal mandate transition`. Cause: the caller attempted an op the state machine forbids (e.g. confirm an already-ACTIVE mandate, suspend a SUSPENDED one, amend a terminal/pending mandate). Action: `GET /mandates/{id}` to read the current `status`; the client should branch on it.

### Idle-expiry sweep

The `MandateExpiryScheduler` cron is **disabled by default** (`openbank.sdd.expiry.enabled=false`). To enable, set the flag and the cron (`openbank.sdd.expiry-cron`); the sweep marks `ACTIVE`/`SUSPENDED` mandates idle for ≥36 months as `EXPIRED`. The pure date arithmetic (`MandateLifecycle.isIdle`) is unit-tested independently of the cron.

## Deploy / release

- Per-service CI builds a fast-jar and a container image (host-side `quarkusBuild`, not in-Docker Gradle — see CLAUDE.md GitOps rules).
- **Release** is automatic via release-please from Conventional Commits (scope `sdd`). Do not hand-edit `version.txt` / `CHANGELOG.md`. `version.txt` is currently `0.2.0`; `quarkus.application.version` in `application.yaml` is `0.1.1` (a known drift — these are reconciled by the `bump` skill on the next change).
- **Flyway** runs at start (`migrate-at-start: true`); `validate-on-migrate` is off and connection retries are configured for cold-start ordering against the DB.
