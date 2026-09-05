# Operations

## Build & run

```bash
# Build (locally)
./gradlew :openbank-sanctions-service:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-sanctions-service:quarkusDev

# Docker (from openbank-infra/)
docker compose build sanctions-service
docker compose up -d sanctions-service
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/sanctions/...` | 8123 | Screening REST API |
| `/api/v1/sanctions/lists/...` | 8123 | List configuration API |
| `/api/v1/info` | 8123 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8123 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8123 | OpenAPI spec |
| `/q/swagger-ui` | 8123 | Swagger UI (dev only) |
| `/q/health` | 8123 | liveness + readiness |
| `/q/metrics` | 8123 | Prometheus |

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | DB host (in docker: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | DB password — **MUST be overridden in prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `REDIS_URL` | `redis://localhost:6379` | Idempotency cache |
| `KEYCLOAK_URL` | `http://localhost:8080` | OIDC issuer |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.sanctions=DEBUG` |

⬜ **This does not happen.** There is no `BootstrapVerifier` in `openbank-libs` (`git grep BootstrapVerifier -- '*.kt'` returns 0) — ADR-0017 prescribes one and its own delivery note records that it was never shipped. Startup is therefore not refused on a dev placeholder, because nothing checks for one.

What actually holds the property: `openbank-infra/gitops/components/sanctions-service/sanctions-service.yaml` takes credentials through `secretKeyRef` from ESO/OpenBao (ADR-0007) and carries no placeholder literal. A configuration property, not a boot-time control (#8426).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer + Redis.

Probe settings (k8s manifest):

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8123 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8123 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="sanctions-service"}` |
| Latency p95 GET | < 80 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST /screen | < 500 ms | includes fuzzy matching + DB write + outbox insert |
| Outbox lag | < 2 s | `sanctions_outbox_pending_age_seconds` |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |
| List freshness | < 24 h | `sanctions_list_last_updated_age_seconds` |

## Runbooks

### Outbox lag growing

1. Check pending count: `SELECT count(*) FROM openbank_sanctions.sanctions_outbox WHERE status='PENDING'`
2. Check Kafka broker reachability: `kcat -L -b kafka:9092`
3. Check dispatcher logs: `kubectl logs -l app=sanctions-service | grep SanctionsOutboxDispatcher`
4. If > 5k PENDING: raise batch size or dispatcher thread count via config map.

### Sanctions list refresh failing

1. Check `sanctions_lists.last_updated_at` for stale timestamps:
   ```sql
   SELECT list_type, last_updated_at, enabled FROM openbank_sanctions.sanctions_lists ORDER BY last_updated_at ASC;
   ```
2. Check for network errors in logs: `kubectl logs -l app=sanctions-service | grep "refresh"`
3. Trigger a manual refresh via API: `POST /api/v1/sanctions/lists/{listType}/refresh`
4. If source URL is unreachable, update it via `PUT /api/v1/sanctions/lists/{id}` with a new `sourceUrl`.

### Large backlog of POTENTIAL_HIT reviews

1. Check the pending queue: `GET /api/v1/sanctions/pending`
2. Alert the compliance team — pending reviews older than 24 h should be escalated.
3. If the backlog is due to a misconfigured threshold (too many fuzzy matches), adjust the `POTENTIAL_HIT` score threshold in service configuration.

### False positive rate too high

Symptom: > 10% of screenings result in `POTENTIAL_HIT` but are cleared in review.

1. Check distribution of `overall_score` for POTENTIAL_HIT records:
   ```sql
   SELECT overall_score, count(*) FROM openbank_sanctions.sanctions_checks
   WHERE status = 'POTENTIAL_HIT' GROUP BY overall_score ORDER BY overall_score;
   ```
2. Consider raising the `POTENTIAL_HIT` threshold from 0.85 to 0.90 in the service config.
3. Review frequently-cleared entities for whitelist candidates.

### DB connection pool exhausted

Pool size: 20 (default). If saturated:

1. Check active queries: `SELECT * FROM pg_stat_activity WHERE application_name='sanctions-service'`
2. Kill long-running: `SELECT pg_cancel_backend(pid)`
3. Raise pool: `quarkus.datasource.jdbc.max-size=40` (via config map).

## Tech-stack version matrix

| Component | Version |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| Gradle | 9.5.1 |
| PostgreSQL driver | 42.7.x |
| Kafka client | 3.7.x |

## Deploy / release

Per-service CI pipeline (`.github/workflows/ci-sanctions-service.yml`):

1. `./gradlew :openbank-sanctions-service:test` — unit + integration tests
2. `./gradlew :openbank-sanctions-service:quarkusBuild` — fast-jar build
3. CycloneDX SBOM generation
4. Docker image build → push to registry
5. CD: ArgoCD picks up the new tag from the GitOps manifest
