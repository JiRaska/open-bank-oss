# Operace

## Build & spuštění

```bash
# Build (lokálně)
./gradlew :openbank-security-scanner:quarkusBuild

# Dev mode (live reload)
./gradlew :openbank-security-scanner:quarkusDev

# Docker (z openbank-infra/)
docker compose build security-scanner
docker compose up -d security-scanner
```

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/security/...` | 8120 | Security scan REST API |
| `/api/v1/ict-incidents/...` | 8120 | API správy ICT incidentů |
| `/api/v1/info` | 8120 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8120 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8120 | OpenAPI spec |
| `/q/swagger-ui` | 8120 | Swagger UI (pouze dev) |
| `/q/health` | 8120 | liveness + readiness |
| `/q/metrics` | 8120 | Prometheus |

## Konfigurace

| Env var | Výchozí hodnota | Účel |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Host DB (v dockeru: `openbank-postgres`) |
| `POSTGRES_PASSWORD` | `openbank_pgpass_local_dev` | Heslo DB — **MUSÍ být přepsáno v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokery |
| `QUARKUS_LOG_LEVEL` | `INFO` | per-package: `com.openbank.securityscanner=DEBUG` |

### Seznam služeb skeneru (application.yaml)

```yaml
openbank:
  security-scanner:
    scan-interval-minutes: 30
    services:
      - name: account-service
        url: http://account-service:8100
        port: 8100
      - name: sanctions-service
        url: http://sanctions-service:8123
        port: 8123
      # ... 25 dalších služeb
```

Pro přidání nové služby do seznamu skenování přidej záznam do `openbank.security-scanner.services` v konfiguraci a nasaď.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — DB connection pool + Kafka producer.

Poznámka: Redis NENÍ závislostí této služby (nepoužívá IdempotencyStore). Spojení do DB se sice
kontroluje, ale žádná byznysová data neobsahuje — viz [04 — Data](./04-data.md).

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,5% (nižší než money-path — není zákaznická) | `up{service="security-scanner"}` |
| Dokončení naplánovaného skenu | < 90 s pro 27 služeb | `security_scan_duration_seconds` |
| Latence p95 GET /report | < 50 ms | in-memory cache |
| ICT incident API p95 | < 200 ms | in-memory zápis + přímý Kafka emit |

## Runbooky

### Naplánovaný sken neběží

1. Zkontroluj logy scheduleru: `kubectl logs -l app=security-scanner | grep scheduledScan`
2. Ověř, že služba běží: `kubectl get pod -l app=security-scanner`
3. Spusť manuálně: `POST /api/v1/security/scan`
4. Zkontroluj konfiguraci: `openbank.security-scanner.scan-interval-minutes` musí být > 0.

### Služba zobrazena jako nedosažitelná v reportu

Příznak: `reachable: false`, grade `F` pro službu.

1. Ověř, že služba běží: `kubectl get pod -l app={service-name}`
2. Ověř URL v konfiguraci `openbank.security-scanner.services`.
3. Zkontroluj network policy — scanner musí mít egress na všechny service porty.
4. Sonda management portu (8085) padá na API URL — pokud ani jedno neodpovídá, služba je skutečně nedostupná.

### Všechny služby zobrazeny jako grade F (CRITICAL)

Pravděpodobně problém s network policy nebo DNS.

1. Zkontroluj Java výjimky v logu: `kubectl logs -l app=security-scanner | grep "java.net"`
2. Ověř DNS z podu skeneru: `kubectl exec -it <scanner-pod> -- nslookup account-service`
3. Ověř, že egress policy povoluje skeneru dosáhnout clusterové služby.

### Event ICT incidentu chybí downstream

Incidenty se vysílají přímo do Kafky bez outboxu, takže selhané publikování nezanechá žádný lokální
záznam, ze kterého by šlo opakovat (#4709).

1. Zkontroluj chyby emitteru: `kubectl logs -l app=security-scanner | grep ict-incident-events-out`
2. Zkontroluj broker: `kcat -L -b kafka:9092`
3. Ověř, že topic event přijal: přečti konec `openbank.security.ict.incident`.
4. Pokud se publikování ztratilo, nahlas incident znovu přes `POST /api/v1/ict-incidents`.

### Výsledky skenů jsou po restartu prázdné

Očekávané chování, ne závada: stav skenů je pouze in-memory. Restartovaný pod vrací prázdný report,
dokud neproběhne první naplánovaný sken (2 minuty po startu, pak každých 30 minut). Okamžitě jej
naplníš přes `POST /api/v1/security/scan`. Rozpracované ICT incidenty se NEVRÁTÍ.

### P1_CRITICAL ICT incident — regulatorní reportování

Časová osa (DORA čl. 17):
1. **T+0** — incident detekován, `POST /api/v1/ict-incidents` s `severity=P1_CRITICAL`
2. **T+4h** — úvodní hlášení ČNB; `POST /api/v1/ict-incidents/{id}/regulatory-report` s `regulatoryReportId`
3. **T+24h** — průběžné hlášení ČNB (pokud dosud nevyřešeno)
4. **T+vyřešeno** — závěrečné hlášení ČNB; `PATCH /status` se `status=RESOLVED` + rto/rpo

## Poznámky k lokálnímu vývoji

Scanner prověřuje služby pomocí URL — v dev módu by seznam služeb měl být přepsán v `application.yaml` tak, aby odkazoval na lokálně běžící služby. Alternativně spusť s `QUARKUS_PROFILE=test`, kde je seznam služeb prázdný a skeny produkují prázdné reporty.
