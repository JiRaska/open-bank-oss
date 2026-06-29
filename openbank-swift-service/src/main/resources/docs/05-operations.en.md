# Operations

## Build & run

```bash
# Build (fast-jar — never uber-jar)
./gradlew :openbank-swift-service:quarkusBuild

# Dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-swift-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

Docker image: multi-stage `Dockerfile` (Eclipse Temurin 25 JDK build → JRE-alpine runtime, non-root `openbank` user, `quarkus-app/` fast-jar layout, `-XX:+UseZGC`, `EXPOSE 8122`). Generic build helper: `openbank-infra/scripts/build-push-service.sh swift-service` (host-side `quarkusBuild`, not in-Docker Gradle).

## Endpoints / ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/swift/...` | 8122 | business REST API |
| `/api/docs` | 8122 | Swagger UI (`always-include: true`) |
| `/api/v1/info` | 8122 | ServiceInfoResource (build metadata) |
| `/q/openapi` | 8085 | OpenAPI document |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | SmallRye Health (liveness + readiness) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is enabled on a separate port (`quarkus.management.port: 8085`, root-path `/q`).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod (Vault)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **MUST be overridden in prod** |
| Kafka bootstrap | `localhost:29092` | `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` |
| Redis hosts | `redis://localhost:6379` | cache/idempotency support |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar base URL (ADR-0034) |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA query path |
| `OPA_TIMEOUT_MS` | `500` | OPA decision timeout |
| `AUTHZ_ENFORCE` | `false` | OPA **advisory by default**; flip to enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata surfaced in `/api/v1/info` |

Security headers are set at the HTTP layer (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, etc.). CORS allows `http://localhost:3000` with `Idempotency-Key` among the allowed headers.

## Resilience (config-driven)

- **Rate limit:** enabled, `max-concurrent-requests: 200`.
- **Circuit breaker:** request-volume-threshold 10, failure-ratio 0.4, success-threshold 3, delay 15s.
- **Retry:** max 2, delay 1s, jitter 0.5s. **Timeout:** 60s.
- **Outbox dispatch** has its own SmallRye Fault Tolerance annotations (see [02 — Architecture](./02-architecture.md)).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC running.
- **Readiness:** `/q/health/ready` (port 8085) — datasource (and configured messaging/redis) reachable.

Graceful shutdown: `quarkus.shutdown.timeout: 30s`.

## Serverless tier (ADR-0057)

`openbank-swift-service` is a **money-path** HTTP service ([ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)). High-value wire instructions are availability-sensitive, so the appropriate tier is **T0 — Always-on** (`minReplicas ≥ 1`, never scales to zero); a move to a lower tier would require the ADR-0030 threat model + 2 approvals and must be justified by measured idle behaviour. The tier is derived from measured traffic, not hand-assigned.

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="swift-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (submit) | < 300 ms | includes validate + DB write |
| Outbox lag | < 5 s (dispatcher polls every 5s) | pending-age on `swift_outbox` |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag / events not flowing

1. Count pending: `SELECT count(*) FROM swift_outbox WHERE status='PENDING'`.
2. Inspect failures: `SELECT event_id, attempt_count, last_error FROM swift_outbox WHERE status='FAILED'`.
3. Check dispatcher logs for `SwiftOutboxDispatcher` (circuit-breaker open?) and Kafka broker reachability.
4. The dispatcher runs every 5s with `concurrentExecution = SKIP`; a stuck publish trips the breaker (opens after 10 calls at 0.5 failure ratio, 5s delay).

### Flyway checksum mismatch on startup

Symptom: `FlywayValidateException`. Cause: an applied migration was edited. Fix: set `QUARKUS_FLYWAY_REPAIR_AT_START=true`, restart, then remove the flag once settled. Never rewrite applied migrations.

### Missing `swift_outbox_seq`

Symptom: outbox INSERT fails with `relation "swift_outbox_seq" does not exist`. Cause: `V3` not applied. Fix: ensure migration V3 ran; covered by `HibernateSequenceGuardTest`.

### Idempotency key conflict

A repeated submit with the same `idempotencyKey` returns the existing message. A `409` indicates a duplicate key with a conflicting payload — do not retry; fix the client or use a new key.

## Tests & coverage

- Tests: `SwiftServiceTest`, `SwiftMessageTest`, `HibernateSequenceGuardTest` (Quarkus JUnit5, AssertJ, MockK).
- Coverage: Kover line floor **40%** (money-path baseline, ratchet-only, per `rules.yaml`); raise as tests land toward the 70% money-path target.

## Deploy / release

- **Release axis:** release-please owns `version.txt` (currently `0.2.0`); never hand-edit. Conventional Commits drive the changelog.
- **API axis:** `openapi.yaml: info.version` (1.0.0) bumped independently from the OpenAPI diff.
- CI is path-scoped (only changed services build). CD via ArgoCD on image-tag bump.
- **Money-path:** every PR needs 2 approvals + the maintained [threat model](../../../../docs/threat-models/openbank-swift-service.md).
