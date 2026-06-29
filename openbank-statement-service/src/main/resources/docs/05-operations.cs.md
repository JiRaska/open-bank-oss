# Provoz

## Build a test

```
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew :openbank-statement-service:build                 # jedna služba
./gradlew :openbank-statement-service:test --offline        # testy (Testcontainers PG per JVM)
./gradlew detekt ktlintCheck koverVerify build              # lokální gate před PR
```

- Runtime stack je reaktivní (`io.smallrye.mutiny.Uni`), ne Kotlin suspend.
- Testy používají izolovaný PostgreSQL per testovací JVM přes Testcontainers (CI infra sweep #578); Kafka je in-memory (`smallrye-reactive-messaging-in-memory`). Lokální profil `*ApiIT`/`%test` cílí na `localhost:5432` databázi `openbank_statement_it`.
- Profil `%test` vypíná scheduler (`quarkus.scheduler.enabled=false`), aby integrační test explicitně řídil `dispatchScheduledBatch()` a nikdy nezávodil s aserce­mi (ADR-0050).

## Image a deploy

- **Vždy fast-jar, nikdy uber-jar** — Dockerfile používá `-Dquarkus.package.jar.type=fast-jar` a runtime stage COPYuje `quarkus-app/`. Generický build: `openbank-infra/scripts/build-push-service.sh openbank-statement-service` (nejdřív host-side `quarkusBuild`, nikdy in-Docker Gradle).
- Nasazováno přes GitOps (ArgoCD). Deployment je připnut na **`replicas: 1`** — to je nosné pro single-writer garanci outboxu (ADR-0050 N4).
- **Serverless tier (ADR-0057):** statement-service je batch/on-demand workload (měsíční cron + interaktivní rendery), přirozený kandidát na scale-to-zero / scale-down tier místo always-hot. Přesný tier potvrď ve FinOps klasifikátoru; není to latency-kritická money-path služba.

## Konfigurace (env)

| Proměnná | Účel | Výchozí |
|---|---|---|
| `POSTGRES_PASSWORD` | heslo DB | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OIDC_CLIENT_SECRET` | OIDC příchozí + M2M client secret | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | URL Keycloak realmu | `http://localhost:8080/realms/openbank` |
| `TRANSACTION_SERVICE_URL` | čtení zaúčtovaných položek | `http://localhost:8102` |
| `BALANCE_SERVICE_URL` | koncový zůstatek pro rekonciliaci | `http://localhost:8105` |
| `ACCOUNT_SERVICE_URL` | info o kapsovém účtu | `http://localhost:8100` |
| `PARTY_SERVICE_URL` | jméno majitele | `http://localhost:8111` |
| `openbank.statement.close-cron` | cron měsíční uzávěrky | `0 30 2 1 * ?` |
| `openbank.statement.scheduled-close.enabled` | zapnout cron | `true` (app), v kódu výchozí false |
| `openbank.outbox.poll-interval` / `initial-delay` | tik dispatche outboxu | `5s` / `5s` |

## Porty a probes

- **App:** 8136. **Management:** 8085, root-path `/q` (health, metriky, docs).
- **Health:** SmallRye Health (`quarkus-smallrye-health`) na `/q/health` (`/live`, `/ready`).
- **Metriky:** Micrometer → Prometheus na `/q/metrics`; čítače kadence uzávěrek přes `CloseMetricsAdapter`.
- **Tracing:** OpenTelemetry OTLP → `http://localhost:4317` (konfigurovatelné), `service.name=openbank-statement-service`.
- **Docs:** Docs-as-Service na `/q/openbank/docs` (ADR-0019).
- **Bezpečnostní hlavičky** nastaveny globálně (CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff atd.).

## SLO (navrhované)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Indikátor | Cíl |
|---|---|
| Latence renderu (camt.053/MT940/PDF) p99 | < 2 s (on-demand, vč. přehrání položek upstreamu) |
| Úspěšnost uzávěrky období (čistá rekonciliace) | ≥ 99,9 % kapes na kadenci |
| Zpoždění dispatche outboxu | < 30 s od uzávěrky do Kafky |
| Měsíční kadence uzávěrek | běží 1. v 02:30; zmeškané běhy se self-heal příští průchod |

## Runbooky

### Uzávěrka období selže s 409 (nesoulad rekonciliace)
Vypočtený koncový zůstatek (`opening ± zaúčtovaný čistý pohyb`) nesouhlasil s koncovým zůstatkem hlášeným balance-service. To je **záměrné** — žádný nekonzistentní výpis se nevydá. Vyšetři: (1) porovnej `delta` chyby uzávěrky; (2) zkontroluj nezaúčtované nebo opožděné transakce v transaction-service pro období; (3) ověř koncový zůstatek balance-service pro kapsu/datum; (4) jakmile zdrojová data souhlasí, spusť uzávěrku znovu (idempotentní). Selhání je zaznamenáno v `statement_close_failure` s `reason=RECONCILIATION`.

### Plánovaná uzávěrka neproběhla / nechala kapsy dlužné
Zkontroluj `GET /api/v1/statements/close-runs/latest` a `/close-runs`. Kadence je self-healing — příští průchod uzavře každý měsíc stále dlužný do předchozího měsíce. Vynucení nyní: `POST /api/v1/statements/close-runs` (operátor). Selhání per-kapsa prohlédni přes `/close-runs/{runId}/failures`.

### Řádek outboxu zaseknutý / DEAD
Opakovaná selhání publikace inkrementují `attempt_count`; při 10 je řádek zaparkován DEAD (vyloučen z dispatche) a zapsán WARN (`statement.outbox.dead`). Vyšetři Kafka konektivitu/topic; po vyřešení vyžaduje DEAD řádek manuální zásah pro re-queue (záměrně se neopakuje automaticky).

### Registr účtů prázdný / plánovaná uzávěrka nic neenumeruje
Registr se doplní z topicu `openbank.accounts.account.created` s `auto.offset.reset=earliest` při prvním nasazení. Pokud je prázdný, ověř, že consumer group `openbank-statement-service` konzumuje a account-service emituje; resetuj offset konzumenta na earliest, pokud je třeba re-doplnění.

### Flyway checksum mismatch při startu
Nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, nech usadit, pak odeber. Nikdy nepřepisuj aplikovanou migraci.
