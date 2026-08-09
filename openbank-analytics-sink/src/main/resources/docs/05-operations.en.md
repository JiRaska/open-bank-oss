# Operations

## Build

```
./gradlew :openbank-analytics-sink:build          # unit-only, infra-free (offline-buildable)
./gradlew :openbank-analytics-sink:build -PwithDocker   # also runs Docker-backed adapter ITs
./gradlew detekt ktlintCheck koverVerify build     # the local gate before a PR
```

- **Tech:** Kotlin / Quarkus 3 LTS / JDK 25 (`jvmToolchain(25)`). Deps: SmallRye Reactive Messaging (Kafka), SmallRye Health, Micrometer/Prometheus, OpenTelemetry, OIDC, Scheduler, Fault Tolerance, kotlinx-coroutines.
- The default `test` task is **unit-only and infra-free** (preserves the offline-buildable promise); the `@Tag("integration")` adapter ITs (ClickHouse / Vault / Apicurio / S3) self-skip without Docker and run only with `-PwithDocker`.
- **SBOM:** CycloneDX (`cyclonedxBom`, schema 1.5, runtime classpath).

> **Dockerfile note:** the current `Dockerfile` builds with `-Dquarkus.package.type=uber-jar` yet the runtime stage COPYs the `quarkus-app/` fast-jar layout. Per the repo's hardened build rule, service images must use **fast-jar** (`-Dquarkus.package.jar.type=fast-jar`); the uber-jar flag leaves `quarkus-app/` empty and crashloops the pod. **This is a known discrepancy to fix** (align the Dockerfile build flag with the COPYed layout, or use `openbank-infra/scripts/build-push-service.sh analytics-sink`). Flagged as a follow-up.

## Configuration (env)

All external dependencies are **opt-in** via env, defaulting to offline no-op/logging bindings:

| Concern | Key env vars | Default |
|---|---|---|
| Sink target | `ANALYTICS_SINK_TYPE` (`clickhouse`), `CLICKHOUSE_URL/DB/USER/PASSWORD` | LoggingAnalyticsSink (offline) |
| Reconciliation | `ANALYTICS_RECONCILE_CRON` (`0 30 2 * * ?`), `ANALYTICS_RECONCILE_SOURCE_BACKEND` (`http`), `..._ENDPOINTS` | warehouse-only, no source |
| Backfill | `ANALYTICS_BACKFILL_CHUNK` (`PT24H`) | — |
| Schema governance | `ANALYTICS_SCHEMA_BACKEND` (`apicurio`), `ANALYTICS_SCHEMA_KNOWN`, `ANALYTICS_SCHEMA_STRICT` | config catalogue, gate open |
| Erasure | `ANALYTICS_ERASURE_BACKEND` (`vault`), `ANALYTICS_VAULT_*` | NoOpCryptoErasure |
| WORM | `ANALYTICS_WORM_BACKEND` (`s3`), `ANALYTICS_WORM_S3_*` | mirror/logging |
| Health / RPO | `ANALYTICS_MAX_LAG_SECONDS` (`900`), `ANALYTICS_MAX_DEAD_LETTERS` (`100`) | — |
| Residency | `ANALYTICS_RESIDENCY_REGION` (`eu-north-1`), `..._ALLOWED`, `..._ENFORCE` (`true`) | enforced |
| Auth | `OIDC_CLIENT_SECRET` | dev placeholder (blocked in prod) |

## Deploy & FinOps tier

- **Off the request path** (asynchronous Kafka consumer + low-traffic operator REST), so this service is a **scale-to-zero candidate** under ADR-0057. The FinOps classifier reads measured signals (Kafka consumer lag + active fraction, HTTP idle ratio, CPU/replica utilisation) and recommends a tier; the lever back to `min>0` is a measured p95 SLO miss. Tier is **unclassified/declared in CI**, not hand-asserted (TBD pending classifier output).
- **Guardrail:** when scaled to zero, Kafka consumer lag must be drained within the RPO; the readiness probe (below) protects against silent lag.

## Health probes

- **Readiness:** `IngestHealthCheck` (`@Readiness`, name `analytics-ingest-freshness`) reports **DOWN** when ingest lag (`now - occurredAt`) exceeds `max-lag-seconds` (default 900s) or the dead-letter count exceeds `max-dead-letters` (default 100). Before the first event it is **UP** (a fresh sink is not "stale").
- **Liveness:** standard SmallRye Health.
- **Startup:** `DataResidencyValidator` aborts boot if `residency.region` is not on the allow-list (when `enforce=true`).
- **Endpoints:** management port 8086, root-path `/q` (`/q/health`, `/q/metrics`, `/q/openbank/docs`).

## Observability

- **Metrics:** Micrometer → Prometheus (`/q/metrics`).
- **Tracing:** OpenTelemetry OTLP → `http://...:4317`, `service.name=openbank-analytics-sink`.
- **Logs:** JSON console logging (`quarkus.log.console.json=true`) in non-dev.

## SLO (proposed)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Ingest freshness (lag) | p95 ≤ 900 s (RPO; readiness gates above this) |
| Dead-letter rate | < 100 outstanding (readiness gates above this) |
| Reconciliation drift | 0 unexplained per-aggregate version mismatches at the daily run |
| Availability (operator REST) | best-effort — off the money path |

## Runbooks

- **Lag / readiness DOWN:** check Kafka consumer-group `analytics-sink` lag and the ClickHouse sink health; if scaled to zero, ensure the workload woke and is draining. Tune `ANALYTICS_MAX_LAG_SECONDS` only with SRE agreement.
- **Dead-letters climbing:** inspect `dead_letter_events.error`; the usual cause is a producer emitting an unknown/newer schema. Once the producer is fixed, an operator replays `raw_payload` through the normal mapping path. If `ANALYTICS_SCHEMA_STRICT=true`, also reconcile the schema catalogue (config or Apicurio).
- **Reconciliation mismatch:** read `GET /api/v1/analytics/reconciliation/last`; raise a **four-eyes backfill** (`POST /backfill/proposals` → approve by a different operator → execute) to refill the gap; the `backfill_audit` row is the evidence.
- **GDPR erasure request:** `POST /api/v1/analytics/erasure`; expect either a crypto-shred or a documented refusal under statutory hold.
- **Integrity challenge:** re-derive leaf hashes from `bronze_events` and verify they reproduce each `integrity_anchors.merkle_root` (authoritative copy in WORM/S3 Object Lock).

## Follow-ups (TBD)

- No checked-in `openapi.yaml` + contract test yet (see [03 — API](./03-api.md)).
- No `version.txt` — the service is **not yet a released component** (not in `release-please-config.json`); `build.gradle.kts` pins `0.1.0-SNAPSHOT`.
- Dockerfile fast-jar vs uber-jar discrepancy (above).
