# Operations

## Build & run

```bash
./gradlew :openbank-balance-service:quarkusBuild
./gradlew :openbank-balance-service:quarkusDev

docker compose -f openbank-infra/docker-compose.yml build balance-service
docker compose -f openbank-infra/docker-compose.yml up -d balance-service
```

## Endpointy

| Path | Port | Účel |
|---|---|---|
| `/api/v1/balances/...` | 8103 | business REST |
| `/api/v1/info` | 8103 | ServiceInfo |
| `/q/openbank/docs` | 8103 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi`, `/q/swagger-ui` | 8103 | API kontrakt + browser UI |
| `/q/health/{live,ready}` | 8085 | probes (separátní mgmt port) |
| `/q/metrics` | 8085 | Prometheus |

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` | `postgresql://localhost:5432/openbank_balance` | DB |
| `QUARKUS_DATASOURCE_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB heslo — prod přes Vault (ADR 0017) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka |
| `QUARKUS_REDIS_HOSTS` | `redis://localhost:6379` | idempotency |
| `OPENBANK_BALANCE_LOW_THRESHOLD_EUR` | `100.00` | trigger `balance.low.v1` |
| `OPENBANK_HOLDS_EXPIRY_INTERVAL` | `5m` | sken expirovaných holdů |

⬜ **Toto se nekoná.** Žádný `BootstrapVerifier` neexistuje (`git grep BootstrapVerifier -- '*.kt'` vrací 0; ADR-0017 ho předepisuje a jeho delivery note uvádí, že dodán nebyl), takže `CHANGE_ME_LOCAL_DEV_ONLY` výše start v prod nepřeruší. V nasazení bere `balance-service.yaml` credentials přes `secretKeyRef` z ESO/OpenBao (ADR-0007) a placeholder v něm není — vlastnost drží konfigurace, ne boot-time kontrola (#8426).

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Availability | 99.95% |
| `GET /balances/{id}` p95 | < 50 ms |
| `POST /holds` p95 | < 100 ms |
| Lag Kafka consumer | < 1 s p99 |
| Outbox lag | < 2 s p99 |

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC
- **Readiness:** `/q/health/ready` — DB pool + Kafka consumer + producer + Redis

## Runbooks

### `optimistic-lock-conflict` rate roste

1. Check Prometheus: `rate(http_server_requests_seconds_count{status="409", code="optimistic-lock-conflict"}[5m])`
2. Pokud > 5 req/s → autorizace karty hammeruje stejný účet. Možné scénáře:
   - dva karty terminals zároveň → expected, klient retry
   - bot fraud testing → kontaktuj fraud-detection
3. Mitigace: rozšiř retry s exponential backoff v transaction-service consumeru

### Booked vs ledger divergence

Denní recon job (viz `04-data.md`) emituje `balance.reconciliation.diverged.v1` event. Akce:

1. Identifikuj postižené účty
2. Replay od minulé známé konzistentní checkpoint časové značky
3. Pokud replay neopraví → manuální adjustment přes audit-trail (vyžaduje schválení 2 compliance officerů)

### Hold expiry worker zaspal

Symptom: `balance_holds WHERE expires_at < now() AND released_at IS NULL` má > 0 řádků > 5 min staré.

1. Check `BalanceOutboxDispatcher` logy
2. Restart pod
3. Pokud problém přetrvává: spusť `POST /q/admin/expire-holds` (interní admin endpoint)

## Tech-stack matrix

Auto z `BuildInfo`:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK | 25 LTS |
| PostgreSQL JDBC | 42.7.x |
| Kafka client | 3.7.x |

## Deploy

CI: `.github/workflows/ci-balance-service.yml` — test → quarkusBuild → SBOM → image push → ArgoCD sync.
