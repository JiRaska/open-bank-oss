# Operations

## Build & run

```bash
# Build (fast-jar — never uber-jar)
./gradlew :openbank-party-service:quarkusBuild

# Dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-party-service:quarkusDev

# Local gate before a PR
./gradlew :openbank-party-service:detekt :openbank-party-service:ktlintCheck \
          :openbank-party-service:koverVerify :openbank-party-service:build
```

Integration tests (`PartyApiIT`) run against a per-JVM PostgreSQL + Redpanda (Kafka API) via Testcontainers (CI infra pilot) rather than the shared compose stack — see `build.gradle.kts`. They inherit the runner's `DOCKER_HOST`, falling back to the unix socket.

## Ports & endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/parties/...` | 8111 | business REST API |
| `/api/v1/info` | 8111 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8111 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is enabled on port **8085** (`quarkus.management`, root-path `/q`). In `%test` the management interface and OIDC are disabled.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod (Vault)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — override in prod |
| (datasource URL) | `postgresql://localhost:5432/openbank_parties` | reactive + JDBC |
| (kafka) | `localhost:29092` | bootstrap servers |
| (oidc) | `http://localhost:8080/realms/openbank` | issuer |
| `OPENBANK_FLAGS_URL` | `http://localhost:8016` | flagd OFREP endpoint (fail-static) |
| `OPENBANK_FLAGS_TIMEOUT_MS` | `100` | flag resolution timeout |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce (ADR-0034) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata in `/api/v1/info` |

Logs are JSON in non-dev profiles; OpenTelemetry OTLP export to `http://localhost:4317`. Security response headers (CSP, HSTS, X-Frame-Options, etc.) are set globally.

## Serverless tier (ADR-0057)

Workload tiering / scale-to-zero is governed by [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md). party-service is a stateful, event-consuming identity service (it must be up to consume KYC/AML events and to be queried during account opening), so it belongs to an always-on tier rather than scale-to-zero. Confirm the exact tier label against the FinOps classifier (TBD — not pinned in this repo's service config).

## Health checks

- **Liveness** `/q/health/live` — JVM + ArC.
- **Readiness** `/q/health/ready` — DB connection + Kafka producer/consumer.

Flyway `connect-retries: 10` / interval `2S` covers DB-not-ready-at-boot.

## SLO (targets)

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="openbank-party-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST create | < 300 ms | DB write + outbox insert |
| Outbox lag | < ~10 s (dispatcher polls every 5 s) | pending-row age |
| KYC/AML event lag | low single-digit seconds | consumer group lag |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox not draining
1. `SELECT count(*) FROM party_outbox WHERE status='PENDING'`.
2. Check Kafka reachability for `openbank.party.events`.
3. Check dispatcher logs: `kubectl logs -l app=party-service | grep PartyOutboxDispatcher`. The circuit breaker may be open — look for repeated `markFailed` + `last_error`.
4. Resolve the downstream/broker issue; the dispatcher retries automatically on the next 5 s tick.

### Party stuck in PENDING_KYC
A party only goes ACTIVE when **both** KYC=APPROVED and AML=CLEARED. Check `kyc_status` and `aml_status` on the row. If one terminal event was never received, confirm kyc-/aml-service emitted it (they are the source of truth and can replay — the consumer is poison-pill safe and acks bad events).

### Party stuck PENDING after both signals
Verify the consumed events used the exact recognised types (`KYC_CASE_APPROVED`/`KYC_CASE_REJECTED`; AML `newStatus/status` = `CLEARED`/`BLOCKED`). Unrecognised values are ignored by design.

### Duplicate-party 409 on create
Email is unique. A 409 means that email already exists — look it up with `GET /parties/search` or by email; do not retry blindly.

### Flyway checksum mismatch at start
Caused by a rewritten applied migration. Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let it settle, then remove. Never edit an applied migration — fix forward (cf. V5→V6).

## Deploy / release

- Per-service path-scoped CI (only builds when `openbank-party-service/**` changes).
- fast-jar Docker image (never uber-jar) via `openbank-infra/scripts/build-push-service.sh party`.
- Versioning is per-service SemVer; `version.txt` is owned by release-please (do not hand-bump). `openapi.yaml:info.version` is the independent API-contract axis (ADR-0048).
