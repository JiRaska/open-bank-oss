# Operations

## Build

```
./gradlew :openbank-sca-service:build
./gradlew detekt ktlintCheck koverVerify build   # local gate before a PR
```

Coverage floor (kover): LINE ≥ 40 (money-path baseline; ratchet-only, target 70). Kover excludes `@Path` / `@ApplicationScoped` / `@RegisterForReflection`-annotated classes.

## Image build & deploy

- **fast-jar, host-side build** (CLAUDE.md GitOps rules). The image is assembled by `.github/workflows/Dockerfile.deploy`: it copies `quarkus-app/` into the `eclipse-temurin:25-jre` runtime base (glibc, #3354) and runs as non-root user `openbank` with `-XX:+UseZGC`. `openbank-sca-service/Dockerfile` builds nothing (#3016) — the pipeline reads exactly one thing from it, `EXPOSE 8110`.
- Generic build: `openbank-infra/scripts/build-push-service.sh sca-service`.
- **Flyway**: `migrate-at-start: true` with 10 connect-retries. If a checksum mismatch ever occurs on a live DB, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` temporarily, then remove once settled (never rewrite an applied migration).

## Ports

| Port | Purpose |
|---|---|
| 8110 | application HTTP (`/api/v1/sca/**`) |
| 8085 | management — health, metrics, docs (`root-path /q`) |

## Health probes

- Liveness/readiness via `quarkus-smallrye-health` at `/q/health` (management port 8085): `/q/health/live`, `/q/health/ready`.
- Datasource, Redis, and Kafka health contribute to readiness through the respective Quarkus extensions.

## Serverless tier (ADR-0057)

sca-service is **money-path ⇒ T0** (`rules.yaml: t0_baseline = money_path_services`). T0 means `min > 0` replicas — it is **not** scaled to zero, because a cold start in the payment-authorisation path would add latency to a synchronous money flow. Demoting it below T0 requires an ADR-0030 threat-model update + 2 approvals.

## Observability

- **Metrics:** Micrometer → Prometheus (`/q/metrics`).
- **Tracing:** OpenTelemetry → OTLP endpoint (`OTEL_EXPORTER_OTLP_ENDPOINT`, default `http://localhost:4317`).
- **Logs:** structured JSON with `traceId` / `spanId` (dev profile switches to plain text).

## Configuration (env)

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD` | DB password (dev placeholder `CHANGE_ME_LOCAL_DEV_ONLY`) |
| `OIDC_CLIENT_SECRET` | Keycloak client secret |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `@Authorize` enforce vs advisory (default `false`) |
| `BUILD_TIME` / `GIT_COMMIT` | BuildInfo for `/api/v1/info` |

`openbank.sca.idempotency-ttl-seconds` (default 300), `openbank.rate-limit.max-concurrent-requests` (100), `openbank.outbox.poll-interval` (5s) are tunable.

## SLO (target)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Availability | 99.9% (money-path, T0) |
| `POST /challenges` p95 latency | < 300 ms (excludes out-of-band user action) |
| Outbox dispatch lag | < 15 s (5 s poll + retry budget) |
| RTO / RPO | 15 min / 5 min |

## Runbooks

### Outbox not draining
Symptoms: `DEVICE_ENROLLED` events not reaching `openbank.sca.challenge.event`; `sca_outbox` rows stuck non-`SENT`.
1. Check Kafka connectivity and the `sca-events-out` channel; the dispatcher circuit breaker may be open (10 req volume, 0.5 failure ratio).
2. Inspect `last_error` on stuck rows. Transient errors are retried (max 2, jitter); persistent failures need the downstream fixed.
3. The dispatcher swallows scheduler errors by design (never crashes the scheduler) — rely on metrics/logs, not pod restarts.

### Push/biometric challenges never complete
Expected when no device decision was posted — **this is correct fail-closed behaviour** (ADR-0021), not a bug. Verify the enrolled device actually called `POST /challenges/{id}/decision` with a valid signature. A signature mismatch returns `401 InvalidDeviceAssertion`; check the device public key and that the signed payload matches `id|decision|amount|currency|creditorIban|reference`.

### Redis unavailable
OTP store, idempotency, and decision store fail. Challenges cannot be created/verified reliably; treat as a hard dependency outage and follow the platform Redis runbook. No durable data is lost (Postgres holds the challenge record).

### Flyway checksum mismatch on startup
Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let the pod start, then remove it. Never edit an applied migration.
