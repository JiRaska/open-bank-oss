# Operace

## Build & spuštění

```bash
# Build (lokálně)
./gradlew :openbank-sanctions-service:quarkusBuild

# Dev mode (live reload)
./gradlew :openbank-sanctions-service:quarkusDev

# Docker (z openbank-infra/)
docker compose build sanctions-service
docker compose up -d sanctions-service
```

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/sanctions/...` | 8123 | Screening REST API |
| `/api/v1/sanctions/lists/...` | 8123 | API konfigurace listin |
| `/api/v1/info` | 8123 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8123 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8123 | OpenAPI spec |
| `/q/swagger-ui` | 8123 | Swagger UI (pouze dev) |
| `/q/health` | 8123 | liveness + readiness |
| `/q/metrics` | 8123 | Prometheus |

## Konfigurace

| Env var | Výchozí hodnota | Účel |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Host DB (v dockeru: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | Heslo DB — **MUSÍ být přepsáno v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokery |
| `REDIS_URL` | `redis://localhost:6379` | Cache idempotence |
| `KEYCLOAK_URL` | `http://localhost:8080` | OIDC issuer |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.sanctions=DEBUG` |

⬜ **Toto se nekoná.** V `openbank-libs` žádný `BootstrapVerifier` není (`git grep BootstrapVerifier -- '*.kt'` vrací 0) — ADR-0017 ho předepisuje a jeho delivery note uvádí, že dodán nebyl. Start se tedy při dev placeholderu neodmítne, protože to nekontroluje nic.

Co drží heslo mimo prod: `openbank-infra/gitops/components/sanctions-service/sanctions-service.yaml` bere credentials přes `secretKeyRef` z ESO/OpenBao (ADR-0007) a neobsahuje žádný placeholder literál. Vlastnost konfigurace, ne boot-time kontrola (#8426).

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer + Redis.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9% | `up{service="sanctions-service"}` |
| Latence p95 GET | < 80 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST /screen | < 500 ms | včetně fuzzy matching + zápis do DB + outbox insert |
| Zpoždění outboxu | < 2 s | `sanctions_outbox_pending_age_seconds` |
| Chybovost | < 0,1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |
| Čerstvost listin | < 24 h | `sanctions_list_last_updated_age_seconds` |

## Runbooky

### Rostoucí zpoždění outboxu

1. Zkontroluj počet čekajících: `SELECT count(*) FROM openbank_sanctions.sanctions_outbox WHERE status='PENDING'`
2. Ověř dostupnost Kafka brokeru: `kcat -L -b kafka:9092`
3. Zkontroluj logy dispatcheru: `kubectl logs -l app=sanctions-service | grep SanctionsOutboxDispatcher`
4. Pokud > 5k PENDING: zvyš batch size nebo počet vláken dispatcheru přes config map.

### Selhání obnovy sankční listiny

1. Zkontroluj zastaralá `last_updated_at` časová razítka:
   ```sql
   SELECT list_type, last_updated_at, enabled FROM openbank_sanctions.sanctions_lists ORDER BY last_updated_at ASC;
   ```
2. Zkontroluj síťové chyby v logu: `kubectl logs -l app=sanctions-service | grep "refresh"`
3. Spusť manuální obnovu přes API: `POST /api/v1/sanctions/lists/{listType}/refresh`
4. Pokud je zdrojová URL nedostupná, aktualizuj ji přes `PUT /api/v1/sanctions/lists/{id}`.

### Velký backlog přezkumů POTENTIAL_HIT

1. Zkontroluj frontu čekajících: `GET /api/v1/sanctions/pending`
2. Upozorni compliance tým — čekající přezkumy starší 24 h by měly být eskalovány.
3. Pokud je backlog způsoben špatně nastaveným prahem (příliš mnoho fuzzy shod), uprav práh skóre `POTENTIAL_HIT` v konfiguraci služby.

### Příliš vysoká míra falešně pozitivních výsledků

Příznak: > 10 % prověření resultuje v `POTENTIAL_HIT`, ale je vyčištěno při přezkumu.

1. Zkontroluj distribuci `overall_score` pro záznamy POTENTIAL_HIT:
   ```sql
   SELECT overall_score, count(*) FROM openbank_sanctions.sanctions_checks
   WHERE status = 'POTENTIAL_HIT' GROUP BY overall_score ORDER BY overall_score;
   ```
2. Zvaž zvýšení prahu `POTENTIAL_HIT` z 0,85 na 0,90 v konfiguraci.
3. Přezkum často čištěných entit pro kandidáty na whitelist.

### Vyčerpání connection poolu DB

Velikost poolu: 20 (výchozí). Při přesycení:

1. Zkontroluj aktivní dotazy: `SELECT * FROM pg_stat_activity WHERE application_name='sanctions-service'`
2. Zabij dlouho běžící: `SELECT pg_cancel_backend(pid)`
3. Zvyš pool: `quarkus.datasource.jdbc.max-size=40` (přes config map).
