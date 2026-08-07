# Operations

## Build & run

```bash
# Build (locally, fast-jar)
./gradlew :openbank-onboarding-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-onboarding-service:quarkusDev

# Docker image (multi-stage; fast-jar, JDK 25 Temurin, ZGC)
openbank-infra/scripts/build-push-service.sh onboarding-service
```

The image is built by `.github/workflows/Dockerfile.deploy` from a host-side fast-jar and runs on `eclipse-temurin:25-jre` (glibc, #3354) as a non-root `openbank` user, entrypoint `java -XX:+UseZGC … -jar /app/quarkus-run.jar`, port 8130. `openbank-onboarding-service/Dockerfile` builds nothing (#3016) — `docker build` against it fails, the context has no `quarkus-app/`; the pipeline reads only its `EXPOSE`.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/onboarding/records` | 8130 | list / detail read API |
| `/api/v1/onboarding/funnel` | 8130 | KPI tile counts |
| `/api/v1/info` | 8130 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8130 | Swagger UI |
| `/q/openapi` | 8130 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

A separate **management interface** is enabled (`quarkus.management.enabled=true`, port `8085`, root-path `/q`), so health, metrics and the docs endpoint are served off the management port, away from the business API on 8130.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod** (Vault, ADR-0017) |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **MUST be overridden in prod** |
| (datasource URL) | `postgresql://localhost:5432/openbank_onboarding` | reactive + JDBC (Flyway) datasource |
| (kafka bootstrap) | `localhost:29092` | `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` |
| (oidc auth server) | `http://localhost:8080/realms/openbank` | OIDC issuer |
| (otel endpoint) | `http://localhost:4317` | OpenTelemetry OTLP exporter |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build identification surfaced in `/api/v1/info` |

The placeholder secrets (`CHANGE_ME_LOCAL_DEV_ONLY`) are dev-only and must be supplied from the platform secret store in any non-dev environment.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running.
- **Readiness:** `/q/health/ready` — reactive PostgreSQL connection + Kafka.

Logs are JSON to console (`quarkus.log.console.json=true`) outside `%dev`; tracing via OpenTelemetry OTLP, metrics via Micrometer/Prometheus.

## Serverless / workload tier (ADR-0057)

This service is a **read-model with bursty, operator-driven traffic** (the cockpit is used during business hours) and is event-fed but tolerant of cold starts (consumers resume from `earliest`). It is a good candidate for a **scale-to-zero / scale-from-zero tier** under the ADR-0057 FinOps classifier — exact tier assignment is **TBD** (driven by the classifier, not hand-set here). Trade-off: a cold start adds latency to the first cockpit query and a brief consumer-lag catch-up.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability (business hours) | 99.9% | Prometheus `up{service="openbank-onboarding-service"}` |
| Latency p95 GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Projection lag (event → row visible) | < 5 s | Kafka consumer lag on the 3 groups |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Consumer lag / cockpit shows stale data

1. Check consumer-group lag for `onboarding-service-party`, `onboarding-service-kyc`, `onboarding-service-sca`.
2. Check pod logs for `OnboardingEventConsumer` poison-pill errors (`grep "Failed to parse"` / `"Projection failed"`). These are acked, not retried — a spike means a producer schema change.
3. If lag persists, confirm Kafka broker reachability and the topic names match (`openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`).

### Read-model looks wrong / needs rebuild

Because the table is a pure projection: stop the service, truncate `onboarding_records`, reset the three consumer-group offsets to `earliest`, and restart. The projection re-seeds from the source event log. No coordination with party/kyc/sca writes is needed (read-only).

### Records never persist (fresh DB)

Symptom: events consumed but `onboarding_records` stays empty with `relation "onboarding_records_seq" does not exist` in logs. Cause: Flyway V2 not applied. Fix: ensure both V1 and V2 ran (`migrate-at-start: true`); V2 creates the Hibernate id sequence. See [04 — Data](./04-data.md).

### DB / startup connection retries

Flyway is configured with `connect-retries: 10` at 2s intervals, so a slow-starting PostgreSQL is tolerated at boot.

## Tech-stack version matrix

Surfaced in the `/api/v1/info` payload:

| Component | Version |
|---|---|
| Kotlin | 2.x (project toolchain) |
| Quarkus | 3.x (`enforcedPlatform(libs.quarkus.bom)`) |
| JDK runtime | 25 (Eclipse Temurin, ZGC) |
| PostgreSQL | 16 |
| Persistence | Hibernate Reactive + Panache (reactive PG client) |
| Messaging | SmallRye Reactive Messaging (Kafka) |

## Deploy / release

- **Released component:** has `version.txt` (currently `0.2.0`); release-please owns the release axis. Do not hand-edit `version.txt` or the changelog.
- **API contract axis:** `openapi.yaml:info.version` (`1.0.0`), independent of the release version (ADR-0048).
- **CI:** path-scoped per-service pipeline; fast-jar build, image build/push, ArgoCD picks up the tag.
- **Review rigour:** although onboarding-service holds no money and no system-of-record state, ADR-0068 places it under **money-path review rigour** (2 approvals + threat model) because it is KYC-decision-adjacent.
