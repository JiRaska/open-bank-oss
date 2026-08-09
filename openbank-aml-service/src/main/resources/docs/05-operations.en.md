# Operations

## Build & run

```bash
# Build (locally) — fast-jar, never uber-jar
./gradlew :openbank-aml-service:quarkusBuild

# Run dev mode (live reload, OIDC disabled in %dev)
./gradlew :openbank-aml-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build

# Container image (single-stage; the recipe is .github/workflows/Dockerfile.deploy)
#   build: host-side, ./gradlew build -Dquarkus.package.jar.type=fast-jar
#   runtime: eclipse-temurin:25-jre (glibc, #3354), non-root user, -XX:+UseZGC
```

> Build uses the host-side Gradle build, fast-jar packaging (`-Dquarkus.package.jar.type=fast-jar`), per the repo GitOps rules — never in-Docker Gradle, never uber-jar.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/aml/cases/...` | 8117 | business REST API |
| `/api/v1/info` | 8117 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8117 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8117 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is on a **separate port 8085** (`quarkus.management.enabled=true`, root-path `/q`). It is disabled under `%test`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — Vault in prod |
| (datasource) | `postgresql://localhost:5432/openbank_aml` | reactive + JDBC URL |
| (kafka) | `localhost:29092` | Kafka bootstrap servers |
| (redis) | `redis://localhost:6379` | idempotency cache |
| (oidc) | `http://localhost:8080/realms/openbank` | OIDC issuer, client `openbank-services` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar PDP (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | flip OPA from advisory to enforce |
| `openbank.aml.auto-clear` | `false` | **sandbox-only** auto-clear of onboarding cases — keep `false` in prod |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata surfaced in `/api/v1/info` |

Logging is JSON to console (`quarkus.log.console.json=true`), human-readable under `%dev`.

## Resilience

Configured under `openbank.resilience` and on the Kafka publisher:
- **Rate limit:** `max-concurrent-requests=100`.
- **Circuit breaker:** requestVolumeThreshold 10, failureRatio 0.5, successThreshold 5, delay 10s.
- **Retry:** maxRetries 2, delay 300ms, jitter 150ms. **Timeout:** 15s (request), 3s (Kafka publish).
- **Publisher:** `@Bulkhead(1,1)`, `@CircuitBreaker`, `@Retry`, `@Timeout(3000)`.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — datasource + Kafka + Redis reachable.

Flyway runs at start (`migrate-at-start=true`, 10 connect retries × 2s) so a cold DB does not crash the pod immediately.

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="aml-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create case) | < 300 ms | DB write + outbox insert |
| Outbox lag | < 10 s | oldest PENDING `aml_outbox` row age (dispatcher runs every 5s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Serverless tier (ADR-0057)

`aml-service` has **no explicit declared tier** in `rules.yaml: finops.tiering.declared` and is not a money-path service, so it inherits the classifier-driven default (unclassified until measured). The outbox dispatcher is `@Scheduled` and the Deployment must run **`replicas: 1`** as the single outbox writer — so scale-to-zero is constrained by the dispatcher: any tiering must keep exactly one writer warm (the cold-start / single-writer guardrails in ADR-0057 apply).

## Runbooks

### Outbox lag growing

1. Count PENDING: `SELECT count(*) FROM aml_outbox WHERE status='PENDING';`
2. Check Kafka reachability: `kcat -L -b kafka:9092`.
3. Check dispatcher logs: `kubectl logs -l app=aml-service | grep AmlOutboxDispatcher`.
4. Confirm exactly one replica (single writer). If a row is stuck FAILED with retries exhausted it moves to DEAD — inspect `last_error`.

### Decision rejected with 409

Symptom: `409 conflict` (`InvalidAmlCaseStateTransitionException`). Cause: the requested `targetStatus` is not a legal transition from the current status, or the case is already terminal (`CLEARED`/`BLOCKED`). Action: re-fetch the case, choose a valid transition; terminal cases cannot be reopened.

### Onboarding cases not clearing in sandbox

Check `openbank.aml.auto-clear` — it is `false` by default. In production this is intentional (analyst decision required). In sandbox, set it `true` to let `PartyEventConsumer` auto-clear onboarding cases.

### Flyway checksum mismatch on startup

Never edit an applied migration. If a checksum mismatch blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the GitOps env, let the DB settle, then remove it.

## Deploy / release

- Per-service CI pipeline (path-scoped): test → fast-jar build → SBOM → image build/push → ArgoCD picks up the tag.
- **Version bump:** any change under `src/main/**` bumps `version.txt` per commit type — but release-please owns the release axis; do not hand-bump `version.txt` against an open Release PR.
- For image-tag merge conflicts in GitOps take `--ours` (the freshly built tag), never blind `--theirs`.

## Tests

- Unit: `AmlCaseServiceTest`, `AmlCaseTest` (state machine), `AmlOutboxDispatchTest`.
- Integration: `AmlOutboxDispatchIT` with `PostgresRedisTestResource` (Testcontainers — isolated Postgres + Valkey per test JVM, CI infra sweep #578). Under `%test` the scheduler is disabled so the IT drives `dispatchScheduledBatch()` explicitly.
- Coverage is ratchet-only (Kover, ADR-0020) — never lower it.
