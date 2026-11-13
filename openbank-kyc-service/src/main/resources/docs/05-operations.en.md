# Operations

## Build & run

```bash
# Build (locally) — host-side quarkusBuild, fast-jar
./gradlew :openbank-kyc-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-kyc-service:quarkusDev

# Container image (generic builder)
openbank-infra/scripts/build-push-service.sh kyc-service
```

The **fast-jar** (`-Dquarkus.package.jar.type=fast-jar`) is built host-side; the image is assembled by `.github/workflows/Dockerfile.deploy`, which copies `quarkus-app/`. Runtime image: `eclipse-temurin:25-jre` (glibc, #3354), non-root `openbank` user, ZGC. Never use uber-jar (empty `quarkus-app/` → crashloop). `openbank-kyc-service/Dockerfile` builds nothing (#3016) — the pipeline reads only its `EXPOSE`.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/kyc/...` | 8114 | business REST API |
| `/api/v1/info` | 8114 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8114 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management endpoints are on the dedicated management port **8085** (`quarkus.management.enabled=true`, root-path `/q`); the business API is on **8114**.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — Vault in prod |
| `BUILD_TIME`, `GIT_COMMIT` | `unknown` | build metadata surfaced on `/api/v1/info` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar (ADR-0034) |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA decision path |
| `AUTHZ_ENFORCE` | `false` | advisory vs enforce authz |
| `openbank.kyc.auto-approve` | `false` | **sandbox-only** straight-through approval; MUST stay false in prod |
| `KYC_SCREENING_RETRY_ENABLED` | `true` | retry of the customer screen for cases opened during a `sanctions-service` outage; `false` stops the retries, not the backlog metric |
| `KYC_SCREENING_RETRY_INTERVAL` / `_MIN_AGE` / `_BATCH_SIZE` | `1m` / `PT2M` / `50` | sweep cadence, wait after the last attempt, cases per run |

Datasource: `postgresql://…/openbank_kyc`. Kafka bootstrap: `localhost:29092` (overridden per environment). Standard security response headers are set in `application.yaml` (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, etc.).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB connection (reactive PG) + Kafka producer/consumer wiring.

Flyway is configured with `connect-retries: 10` (2s interval) so startup tolerates a DB that is still coming up.

## Serverless tier (ADR-0057)

`kyc-service` is a request-driven compliance back-office service (no continuous hot path); it is a candidate for a **scale-to-zero / scale-down** workload tier under ADR-0057. One caveat: it runs a Kafka **consumer** (`party-events-in`) and a 5s outbox `@Scheduled` dispatcher, so a fully scaled-to-zero deployment would pause auto-open and event drain — pick the tier accordingly (keep ≥1 replica if continuous consumption is required). Confirm the assigned tier against the FinOps classifier.

## SLO (indicative)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Availability | 99.9% (back-office, business hours weighted) |
| Read latency (p99) | < 200 ms |
| Mutation latency (p99) | < 500 ms |
| Outbox drain lag | < 15 s (dispatcher every 5 s, batch 25) |
| RTO / RPO | 15 min / 5 min (DORA-aligned) |

## Runbooks

- **Outbox backlog growing** — check `kyc_outbox` rows with `status != SENT` and rising `attempt_count` / `last_error`; verify Kafka reachability and the `@CircuitBreaker` state in the dispatcher.
- **Cases not auto-opening** — verify the `party-events-in` consumer is joined to group `kyc-service-party` and that `PARTY_CREATED` is flowing on `openbank.party.events`. `openCaseForParty` is idempotent; safe to replay the topic.
- **Duplicate-case error on insert** — expected under replay/scale-out; `uq_kyc_cases_active_party` rejects the loser and the code re-reads. No action unless errors persist for distinct parties.
- **Flyway checksum mismatch on startup** — never rewrite an applied migration; set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in GitOps env, then remove once settled.
- **Cases left unscreened** — `openbank_kyc_sanctions_unscreened_cases{service="kyc"}` counts in-flight cases whose sanctions check is `PENDING` and was never screened; `openbank_kyc_sanctions_unscreened_oldest_age_seconds` is the age of the oldest (both read NaN until the first measurement after start). A number that does not fall while `sanctions-service` is healthy means the sweep is not running (heartbeat `kyc-unscreened-case-retry`, `WorkflowLivenessStale`), is disabled, or holds no name for those cases — `SELECT case_id FROM kyc_cases WHERE pending_screening_name IS NULL AND status NOT IN ('APPROVED','REJECTED','EXPIRED')` lists candidates for a reviewer. The log line `kyc.unscreened-case.retry attempted=… settled=… failed=…` reports each run; it never contains a name.
- **Sandbox auto-approve in prod** — if cases approve with reviewer `sandbox-auto-approval` in a non-sandbox environment, `openbank.kyc.auto-approve` is mis-set; flip to `false` immediately (compliance incident, four-eyes bypass).

## Re-screening backlog

Alerts `KycRescreenBacklogOverdue` (critical) and `KycRescreenLoopDisabledWithPending` (warning), issue #15961. Metric definitions and cold-pod values: [07-screening-triggers](07-screening-triggers.en.md#backlog-metrics-and-alerts).

- **`KycRescreenBacklogOverdue`** — a `kyc_screening_triggers` row has been PENDING or OPEN_FAILED for more than 24 hours, so a customer a sanctions list change could match has no re-screening case. List the backlog: `SELECT state, failed_reason, attempts, next_attempt_at, created_at FROM kyc_screening_triggers WHERE state IN ('PENDING','OPEN_FAILED') ORDER BY created_at LIMIT 50`. Rows with a `next_attempt_at` are still being retried: check the case coordinator and the `kyc.screening-trigger.retry` heartbeat (`WorkflowLivenessStale`). Rows with `next_attempt_at` NULL and state OPEN_FAILED are parked after the attempt budget; each has a `KYC_COORDINATOR_OPEN_FAILED` event and needs an operator to open the case and reconcile the row (see "Recovery ownership" in 07-screening-triggers).
- **`KycRescreenLoopDisabledWithPending`** — `KYC_SCREENING_TRIGGERS_ENABLED` or `KYC_SCREENING_TRIGGERS_RETRY_ENABLED` is false while undrained rows remain. Re-enable the loop or reconcile the rows by hand; do not delete them. While the loop is off, new list changes are not queued at all: they are acked and counted as `openbank_kyc_sanctions_list_change_events_total{outcome="disabled"}`.
- **No data / NaN** — the gauges read NaN until the first database sample after start (1 minute). NaN that persists means the sampler is failing; the `kyc-rescreen-backlog-sample` heartbeat goes stale and `WorkflowLivenessStale` fires.

## Observability

OpenTelemetry OTLP traces to `:4317` (`service.name=openbank-kyc-service`), Micrometer/Prometheus metrics on `/q/metrics`, JSON console logging. Build metadata (`gitCommit`, `buildTime`, version) on `/api/v1/info` (DORA Art. 9 identification).

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
