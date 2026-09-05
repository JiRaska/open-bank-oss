# Operations

## Build & run

```bash
# Build (lokálně)
./gradlew :openbank-account-service:quarkusBuild

# Run dev mode (live reload)
./gradlew :openbank-account-service:quarkusDev

# Docker (z openbank-infra/)
docker compose build account-service
docker compose up -d account-service
```

## Endpointy

| Path | Port | Účel |
|---|---|---|
| `/api/v1/accounts/...` | 8100 | business REST API |
| `/api/v1/authorizations/...` | 8100 | správa oprávnění |
| `/api/v1/info` | 8100 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8100 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8100 | OpenAPI spec |
| `/q/swagger-ui` | 8100 | Swagger UI (jen dev) |
| `/q/health` | 8100 | liveness + readiness |
| `/q/metrics` | 8100 | Prometheus |

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | DB host (in docker: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | DB password — **MUSÍ být přepsán v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `REDIS_URL` | `redis://localhost:6379` | Idempotency cache |
| `KEYCLOAK_URL` | `http://localhost:8080` | OIDC issuer |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.account=DEBUG` |

⬜ **Toto se nekoná.** V `openbank-libs` žádný `BootstrapVerifier` není (`git grep BootstrapVerifier -- '*.kt'` vrací 0) — ADR-0017 ho předepisuje a jeho delivery note uvádí, že dodán nebyl. Start se tedy při dev placeholderu v prod profilu nepřeruší, protože to nekontroluje nic.

Co drží heslo mimo prod: nasazený manifest `openbank-infra/gitops/components/accounts/account-service.yaml` bere credentials přes `secretKeyRef` z ESO/OpenBao (ADR-0007) a neobsahuje žádný `CHANGE_ME` ani `_local_dev_only` literál. Je to vlastnost konfigurace, ne boot-time kontrola — nasazení se špatnou hodnotou nebude odmítnuto, jen selže při připojení k databázi (#8426).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Restart pod při fail.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer + Redis.

Probe nastavení v `k8s/account-service.yaml`:

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

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Availability | 99.9% (8.76h/rok downtime) | Prometheus `up{service="account-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (open account) | < 300 ms | obsahuje DB write + outbox insert |
| Outbox lag | < 2 s | `account_outbox_pending_age_seconds` (custom metric) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox lag roste

1. Check `account_outbox` PENDING count: `SELECT count(*) FROM account.account_outbox WHERE status='PENDING'`
2. Check Kafka broker reachability: `kcat -L -b kafka:9092`
3. Check dispatcher logs: `kubectl logs -l app=account-service | grep AccountOutboxDispatcher`
4. Pokud > 10k PENDING: zvyš batch size (`OUTBOX_BATCH_SIZE=500`) a počet dispatcher threadů.

### Idempotency key conflict

Symptom: 409 `idempotency-key-mismatch`. Příčina: klient reuse key s jiným payloadem. Akce: NE retry — fix client, nebo nový key.

### DB connection pool exhausted

Pool size: 30 (default). Pokud saturated:

1. Check active queries: `SELECT * FROM pg_stat_activity WHERE datname='openbank' AND application_name='account-service'`
2. Long-running TX? Kill: `SELECT pg_cancel_backend(pid)`
3. Zvyš pool: `quarkus.datasource.reactive.max-size=50` (přes config map).

## Tech-stack version matrix

Auto-generated z `libs.versions.toml` při buildu, dostupné v `/api/v1/info` payloadu:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| Gradle | 9.5.1 |
| PostgreSQL driver | 42.7.x |
| Kafka client | 3.7.x |
| Jackson | 2.18.x |

## Lokální dev

```bash
# Spusť infra (postgres, kafka, redis, keycloak)
make up

# Spusť service v dev mode
./gradlew :openbank-account-service:quarkusDev

# Test endpoint
curl -H "Authorization: Bearer $(./scripts/get-token.sh)" \
     http://localhost:8100/api/v1/accounts

# Live edit kódu → automatic restart, no rebuild needed
```

## Deploy / release

CI per-service pipeline (`.github/workflows/ci-account-service.yml`):

1. `./gradlew :openbank-account-service:test` — unit + integration testy
2. `./gradlew :openbank-account-service:quarkusBuild` — fast-jar build
3. CycloneDX SBOM generation
4. Docker image build → push to registry
5. CD: ArgoCD picks up new tag from `k8s/account-service.yaml` bump
