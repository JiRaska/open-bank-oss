# Operations

## Build & run

```bash
# Build (locally, fast-jar — never uber-jar)
./gradlew :openbank-pid-service:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-pid-service:quarkusDev

# Container (fast-jar, runs as non-root user `openbank`)
openbank-infra/scripts/build-push-service.sh pid-service
```

The image is assembled by `.github/workflows/Dockerfile.deploy` from a host-side fast-jar: it copies the `quarkus-app/` layout into the `eclipse-temurin:25-jre` runtime base (glibc, #3354) and starts with `-XX:+UseZGC`. `openbank-pid-service/Dockerfile` builds nothing (#3016) — the pipeline reads exactly one thing from it, the `EXPOSE` line.

## Endpoints / ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/parties/...` | 8105 | business REST API |
| `/api/v1/info` | 8105 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Quarkus **management interface is enabled on port 8085** (`quarkus.management.enabled=true`, root-path `/q`). Health, metrics, OpenAPI and the docs resource are served there; the business API stays on 8105. The Dockerfile `EXPOSE`s 8105 — ensure 8085 is also exposed/scraped in the k8s manifest.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` | `postgresql://localhost:5432/openbank_pid` | reactive DB URL |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://localhost:5432/openbank_pid` | JDBC URL (Flyway) |
| `QUARKUS_DATASOURCE_USERNAME` / `_PASSWORD` | `openbank` / `CHANGE_ME_LOCAL_DEV_ONLY` | DB creds — **override in prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | Keycloak issuer |
| `QUARKUS_OIDC_CLIENT_ID` / `_CREDENTIALS_SECRET` | `openbank-services` / `CHANGE_ME...` | OIDC client |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry traces |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | `@Authorize` enforce vs advisory |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata for `/api/v1/info` |
| `QUARKUS_LOG_LEVEL` | `INFO` | log level |

Security headers (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) are set globally in `application.yaml`.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC. Pod restart on failure.
- **Readiness:** `/q/health/ready` — reactive DB pool + Kafka producer (SmallRye Health).

Probe example (port 8085 management):

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8085 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8085 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Workload tier (ADR-0057)

pid-service is **not money-path** and identity reads/writes are bursty rather than constant. It is a candidate for a **scale-to-zero / scale-from-zero tier** under the FinOps workload classifier (ADR-0057). Caveat: the outbox `@Scheduled(every = "5s")` dispatcher needs at least one running replica to drain `pid_outbox`, so a scale-to-zero policy must keep a warm replica (or move dispatch to a KEDA cron/Kafka-lag trigger). Tier classification is TBD — confirm against the deployed manifest.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="pid-service"}` |
| Latency p95 GET `/parties/{id}` | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST `/parties` | < 300 ms | includes DB write + event publish |
| Outbox lag | < 10 s | age of oldest `pid_outbox` row with `status != SENT` (poll interval is 5 s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing
1. Count stuck rows: `SELECT count(*) FROM pid_outbox WHERE status <> 'SENT';`
2. Inspect failures: `SELECT event_id, attempt_count, last_error FROM pid_outbox WHERE status='FAILED' ORDER BY updated_at DESC LIMIT 20;`
3. Check the circuit breaker — repeated `@Timeout`/Kafka errors open it for ~5 s. Verify broker reachability.
4. Check the dispatcher is running on ≥ 1 replica (`@Scheduled` only fires on a live pod).

### Duplicate-identity / 409 on create
Symptom: `409 CONFLICT` "Party with bankID sub … already exists" or external-id unique violation. Cause: the bankID `sub` (or another `(id_type,id_value)`) is already mapped. Action: resolve the existing party via `GET /by-external-id?type=BANKID_SUB&value=…` and reuse it — do not force a second party (one-person = one-party invariant).

### Invalid PID case transition (400)
Symptom: `400 VALIDATION_ERROR` from `/parties/{id}/case`. Cause: the requested `status` is not a legal next state from the current case status. Action: read current `caseLifecycle.status` and pick a permitted transition (the `CaseTransitionEngine` rules define the graph).

### DB connection issues
Reactive PG pool exhausted → readiness fails. Check `pg_stat_activity` for `application_name` of the service; kill long-running queries; raise pool size via config map.

## Tech-stack version matrix

Versions are pinned centrally in `libs.versions.toml` (Quarkus BOM) and surfaced in `/api/v1/info`.

| Component | Version |
|---|---|
| Kotlin | per root toolchain (Kotlin 2.x) |
| Quarkus | per `libs.quarkus.bom` (3.x LTS) |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL driver | Quarkus reactive PG + JDBC |
| Kafka client | SmallRye Reactive Messaging |

(Exact pinned versions are read from `gradle/libs.versions.toml` at build time — see `/api/v1/info`.)

## Deploy / release

- **Versioning:** `version.txt` (currently `0.3.0`) owned by release-please; conventional commits drive the bump. The release axis is independent from `openapi.yaml:info.version` (ADR-0048).
- **CI:** path-scoped per-service pipeline (build only on changes under `openbank-pid-service/src/main/**`); `detekt`, `ktlintCheck`, `koverVerify`, tests, SBOM, image build.
- **CD:** ArgoCD picks up the new image tag from the GitOps manifest.
