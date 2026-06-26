# Operations

## Build & run

```bash
# Build (fast-jar — never uber-jar, per CLAUDE.md GitOps rules)
./gradlew :openbank-clearing-service:quarkusBuild

# Dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-clearing-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build
```

Coverage floor: Kover LINE bound `minValue = 40` (excludes `@Path`, `@ApplicationScoped`, `@RegisterForReflection` annotated classes). As a money-path service, the effective floor ratchets higher over time and must never be lowered.

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/clearing/...` | 8124 | business REST API |
| `/api/v1/info` | 8124 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 (mgmt) | **Docs-as-Service** (this documentation) |
| `/api/docs` | 8124 | Swagger UI (`always-include: true`) |
| `/q/openapi` | 8124 | OpenAPI spec |
| `/q/health` | 8085 (mgmt) | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 (mgmt) | Prometheus (Micrometer) |

The **management interface** is enabled (`quarkus.management.enabled: true`) on port **8085**, host `0.0.0.0`, root-path `/q`. Application HTTP is on **8124**.

## Configuration

| Config / env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `quarkus.datasource.reactive.url` | `postgresql://localhost:5432/openbank_clearing` | DB |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — override in prod |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | OIDC issuer |
| `quarkus.redis.hosts` | `redis://localhost:6379` | Valkey |
| Kafka topic | `openbank.clearing.batch.event` | channel `clearing-events-out` |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce |
| `openbank.clearing.batch-size` | `1000` | max items pulled per cycle |
| `openbank.clearing.netting-enabled` | `true` | net settlement |
| `openbank.clearing.settlement-cycle-hours` | `4` | cycle cadence (config; scheduling TBD) |
| `openbank.outbox.poll-interval` / `initial-delay` | `5s` / `5s` | outbox dispatcher cadence |
| `openbank.rate-limit.max-concurrent-requests` | `500` | concurrency guard |

The `CHANGE_ME_LOCAL_DEV_ONLY` placeholders must be replaced by Vault-injected secrets in production (ADR-0017).

## Health checks

- **Liveness:** `/q/health/live` (mgmt port 8085) — JVM + ArC running.
- **Readiness:** `/q/health/ready` — DB / Kafka producer readiness.

Security response headers are set globally in `application.yaml` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`, HSTS, etc.).

## Serverless / workload tier (ADR-0057)

Clearing is a **money-path** service. Under the ADR-0057 four-tier model the candidate tier depends on traffic shape:

- The settlement work is inherently **periodic** (`settlement-cycle-hours: 4`) and the outbox dispatcher is a resident `@Scheduled` loop, which points toward **T2 (event/consumer)** with a resident pod rather than scale-to-zero.
- A money-path *hot* path may not scale-to-zero where a cold-start gap is unacceptable (**T0 — Always-on**, `minReplicas ≥ 1`).
- The actual tier is **derived from measured traffic** by the FinOps classifier, not hand-assigned here — treat the binding tier as **TBD / classifier-derived**.

## SLO (targets)

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="openbank-clearing-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds` |
| Latency p95 submit | < 300 ms | DB write |
| Outbox lag | < 10 s (poll 5 s) | pending-age on `clearing_outbox` |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing
1. `SELECT count(*) FROM clearing_outbox WHERE status='PENDING';`
2. Check Kafka reachability for topic `openbank.clearing.batch.event`.
3. Inspect dispatcher logs: `kubectl logs -l app=openbank-clearing-service | grep ClearingOutboxDispatcher`.
4. The dispatcher batch size is 25 with circuit-breaker/bulkhead; persistent FAILED rows carry `last_error`.

### Settle/trigger returns 500
Body is `{ "error": "<message>" }`. Common cause: `settleBatch` with an unknown id → `IllegalArgumentException("Batch not found")`. Verify the batch id; reads via `GET /batches/{id}`.

### Flyway checksum mismatch on start
Never edit applied migrations. If a live DB blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` temporarily (per CLAUDE.md), then remove once settled. Note `validate-on-migrate` is already `false` here.

### DB connection issues at boot
`flyway.connect-retries: 10`, `connect-retries-interval: 2S` — the service retries DB connection on startup; check Postgres availability if it loops.

## Deploy / release

- Per-service path-scoped CI builds only the changed service. Image build is **fast-jar**, host-side Gradle (`openbank-infra/scripts/build-push-service.sh openbank-clearing-service`).
- **Release axis:** release-please owns `version.txt` (currently `0.2.0`); do not hand-bump.
- **GitOps image-tag conflicts:** take `--ours` for image lines (CLAUDE.md), never blind `--theirs`.
- **Money-path gate:** PRs need 2 approvals + an up-to-date threat model (`docs/threat-models/openbank-clearing-service.md`).
