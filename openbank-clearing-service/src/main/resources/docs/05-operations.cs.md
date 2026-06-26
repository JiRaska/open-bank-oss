# Provoz

## Build & běh

```bash
# Build (fast-jar — nikdy uber-jar, dle GitOps pravidel CLAUDE.md)
./gradlew :openbank-clearing-service:quarkusBuild

# Dev mód (live reload; OIDC vypnut v %dev)
./gradlew :openbank-clearing-service:quarkusDev

# Lokální gate před PR
./gradlew detekt ktlintCheck koverVerify build
```

Coverage floor: Kover LINE bound `minValue = 40` (vyjímá třídy anotované `@Path`, `@ApplicationScoped`, `@RegisterForReflection`). Jako money-path služba se efektivní floor postupně zvyšuje a nesmí být nikdy snížen.

## Endpointy & porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/clearing/...` | 8124 | byznys REST API |
| `/api/v1/info` | 8124 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 (mgmt) | **Docs-as-Service** (tato dokumentace) |
| `/api/docs` | 8124 | Swagger UI (`always-include: true`) |
| `/q/openapi` | 8124 | OpenAPI spec |
| `/q/health` | 8085 (mgmt) | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 (mgmt) | Prometheus (Micrometer) |

**Management rozhraní** je zapnuto (`quarkus.management.enabled: true`) na portu **8085**, host `0.0.0.0`, root-path `/q`. Aplikační HTTP běží na **8124**.

## Konfigurace

| Konfig / env var | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v produkci MUSÍ být přepsáno přes Vault** |
| `quarkus.datasource.reactive.url` | `postgresql://localhost:5432/openbank_clearing` | DB |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — v produkci přepsat |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | OIDC issuer |
| `quarkus.redis.hosts` | `redis://localhost:6379` | Valkey |
| Kafka topic | `openbank.clearing.batch.event` | kanál `clearing-events-out` |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce |
| `openbank.clearing.batch-size` | `1000` | max položek načtených na cyklus |
| `openbank.clearing.netting-enabled` | `true` | net settlement |
| `openbank.clearing.settlement-cycle-hours` | `4` | kadence cyklu (konfig; rozvrhování TBD) |
| `openbank.outbox.poll-interval` / `initial-delay` | `5s` / `5s` | kadence outbox dispatcheru |
| `openbank.rate-limit.max-concurrent-requests` | `500` | guard souběžnosti |

Placeholdery `CHANGE_ME_LOCAL_DEV_ONLY` musí být v produkci nahrazeny tajemstvími vstříknutými z Vaultu (ADR-0017).

## Health checky

- **Liveness:** `/q/health/live` (mgmt port 8085) — JVM + ArC běží.
- **Readiness:** `/q/health/ready` — připravenost DB / Kafka produceru.

Bezpečnostní response hlavičky jsou nastaveny globálně v `application.yaml` (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`, HSTS atd.).

## Serverless / workload tier (ADR-0057)

Clearing je **money-path** služba. V modelu čtyř tierů ADR-0057 závisí kandidátní tier na tvaru provozu:

- Zúčtovací práce je inherentně **periodická** (`settlement-cycle-hours: 4`) a outbox dispatcher je rezidentní `@Scheduled` smyčka, což ukazuje spíše na **T2 (event/consumer)** s rezidentním podem než na scale-to-zero.
- Money-path *hot* cesta nemusí scale-to-zero tam, kde je mezera cold-startu nepřijatelná (**T0 — Always-on**, `minReplicas ≥ 1`).
- Skutečný tier je **odvozen z naměřeného provozu** FinOps klasifikátorem, ne ručně přiřazen zde — vázaný tier berte jako **TBD / odvozený klasifikátorem**.

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99.9 % | Prometheus `up{service="openbank-clearing-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds` |
| Latence p95 submit | < 300 ms | zápis do DB |
| Outbox lag | < 10 s (poll 5 s) | stáří pending na `clearing_outbox` |
| Chybovost | < 0.1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Roste outbox lag
1. `SELECT count(*) FROM clearing_outbox WHERE status='PENDING';`
2. Zkontroluj dosažitelnost Kafky pro topic `openbank.clearing.batch.event`.
3. Prohlédni logy dispatcheru: `kubectl logs -l app=openbank-clearing-service | grep ClearingOutboxDispatcher`.
4. Batch size dispatcheru je 25 s circuit-breakerem/bulkheadem; trvale FAILED řádky nesou `last_error`.

### Settle/trigger vrací 500
Tělo je `{ "error": "<zpráva>" }`. Častá příčina: `settleBatch` s neznámým id → `IllegalArgumentException("Batch not found")`. Ověř id dávky; čtení přes `GET /batches/{id}`.

### Flyway checksum mismatch při startu
Nikdy needituj aplikované migrace. Pokud živá DB blokuje start, dočasně nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` (dle CLAUDE.md), poté po ustálení odstraň. `validate-on-migrate` je zde už `false`.

### Problémy s připojením DB při bootu
`flyway.connect-retries: 10`, `connect-retries-interval: 2S` — služba při startu opakuje připojení k DB; pokud se zacyklí, ověř dostupnost Postgresu.

## Deploy / release

- Per-service path-scoped CI buildí jen změněnou službu. Image build je **fast-jar**, host-side Gradle (`openbank-infra/scripts/build-push-service.sh openbank-clearing-service`).
- **Release osa:** release-please vlastní `version.txt` (aktuálně `0.2.0`); ručně nebumpovat.
- **Konflikty image tagů v GitOps:** u image řádků ber `--ours` (CLAUDE.md), nikdy slepě `--theirs`.
- **Money-path gate:** PR vyžadují 2 schválení + aktuální threat model (`docs/threat-models/openbank-clearing-service.md`).
