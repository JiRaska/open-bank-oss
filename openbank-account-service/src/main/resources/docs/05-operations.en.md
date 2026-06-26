# Operations

## Build & run

```bash
# Build (locally)
./gradlew :openbank-account-service:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-account-service:quarkusDev

# Docker (from openbank-infra/)
docker compose build account-service
docker compose up -d account-service
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/accounts/...` | 8100 | business REST API |
| `/api/v1/authorizations/...` | 8100 | authorization management |
| `/api/v1/info` | 8100 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8100 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8100 | OpenAPI spec |
| `/q/swagger-ui` | 8100 | Swagger UI (dev only) |
| `/q/health` | 8100 | liveness + readiness |
| `/q/metrics` | 8100 | Prometheus |

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | DB host (in docker: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | DB password — **MUST be overridden in prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `REDIS_URL` | `redis://localhost:6379` | Idempotency cache |
| `KEYCLOAK_URL` | `http://localhost:8080` | OIDC issuer |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.account=DEBUG` |

`BootstrapVerifier` from `openbank-libs` fails to start when run with `QUARKUS_PROFILE=prod` and it detects dev placeholders in secrets — addressed by ADR 0017 (Vault).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer + Redis.

Probe settings in `k8s/account-service.yaml`:

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8100 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8100 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## SLO

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% (8.76 h/year downtime) | Prometheus `up{service="account-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (open account) | < 300 ms | includes DB write + outbox insert |
| Outbox lag | < 2 s | `account_outbox_pending_age_seconds` (custom metric) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag growing

1. Check `account_outbox` PENDING count: `SELECT count(*) FROM account.account_outbox WHERE status='PENDING'`
2. Check Kafka broker reachability: `kcat -L -b kafka:9092`
3. Check dispatcher logs: `kubectl logs -l app=account-service | grep AccountOutboxDispatcher`
4. If > 10k PENDING: raise batch size (`OUTBOX_BATCH_SIZE=500`) and dispatcher thread count.

### Idempotency key conflict

Symptom: 409 `idempotency-key-mismatch`. Cause: the client reused the key with a different payload. Action: do NOT retry — fix the client, or use a new key.

### DB connection pool exhausted

Pool size: 30 (default). If saturated:

1. Check active queries: `SELECT * FROM pg_stat_activity WHERE datname='openbank' AND application_name='account-service'`
2. Long-running TX? Kill: `SELECT pg_cancel_backend(pid)`
3. Raise pool: `quarkus.datasource.reactive.max-size=50` (via config map).

## Tech-stack version matrix

Auto-generated from `libs.versions.toml` at build, available in the `/api/v1/info` payload:

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| Gradle | 9.5.1 |
| PostgreSQL driver | 42.7.x |
| Kafka client | 3.7.x |
| Jackson | 2.18.x |

## Local dev

```bash
# Start infra (postgres, kafka, redis, keycloak)
make up

# Run service in dev mode
./gradlew :openbank-account-service:quarkusDev

# Test endpoint
curl -H "Authorization: Bearer $(./scripts/get-token.sh)" \
     http://localhost:8100/api/v1/accounts

# Live edit code → automatic restart, no rebuild needed
```

## Deploy / release

Per-service CI pipeline (`.github/workflows/ci-account-service.yml`):

1. `./gradlew :openbank-account-service:test` — unit + integration tests
2. `./gradlew :openbank-account-service:quarkusBuild` — fast-jar build
3. CycloneDX SBOM generation
4. Docker image build → push to registry
5. CD: ArgoCD picks up the new tag from `k8s/account-service.yaml` bump
