# Provoz

## Build & běh

```bash
# Build (lokálně, host-side fast-jar — nikdy uber-jar)
./gradlew :openbank-ledger-service:quarkusBuild

# Dev mód (live reload; chování OIDC + scheduleru upraveno přes %dev)
./gradlew :openbank-ledger-service:quarkusDev

# Lokální brána před PR
./gradlew :openbank-ledger-service:detekt ktlintCheck koverVerify build

# Build/push image (nejprve host-side build)
openbank-infra/scripts/build-push-service.sh ledger-service
```

Build používá convention plugin `openbank.quarkus-service` (ADR-0049 D1). Kover floor je vynucen na **40 %** řádků (`koverVerify` napojeno na `check`); money-path cíl je 70 %. Provider-side Pact verifikace běží proti sdílenému adresáři `pacts/` (ADR-0063).

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/journals/...` | 8101 | obchodní REST API (kniha) |
| `/api/v1/ledger/fx-revaluation` | 8101 | ops trigger FX revalvace |
| `/api/v1/info` | 8101 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/swagger-ui` | 8085 | Swagger UI (jen dev) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management endpointy jsou na dedikovaném **management portu 8085** (`quarkus.management.root-path: /q`); obchodní API je na **8101**.

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` / `..._JDBC_URL` | `…localhost:5432/openbank_ledger` | reaktivní (app) + JDBC (Flyway) datasource |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ být přepsáno přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | (cluster) | Kafka brokeři pro outbox topic |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `FX_SERVICE_URL` | `http://localhost:8119` | REST base fx-service (kurzy ČNB) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata pro `/api/v1/info` |
| `openbank.ledger.partition.*` | viz níže | knoby životního cyklu partitionů |

Knoby partitionů (`application.yaml`): `future-years: 2`, `retention-years: 10`, `drop-enabled: false`, `dry-run: true`. Přepnutí `drop-enabled`/`dry-run` je vědomá, archivovaná operátorská akce — DROP je fyzicky destruktivní.

Bezpečnostní response hlavičky (CSP, HSTS, X-Frame-Options DENY, nosniff, atd.) jsou nastaveny v `application.yaml`. Rate limiting: `openbank.rate-limit` (max 100 souběžných požadavků).

## Serverless tier (ADR-0057)

ledger-service je **money-path / T0** služba. T0 je nejvyšší tier dostupnosti a **nescaluje se na nulu** — je vždy zapnutá s `replicas: 1` (invariant jediného zapisovatele outboxu, ADR-0050 N4). Scale-to-zero klasifikátor money-path služby explicitně vylučuje; demotace z T0 by vyžadovala ADR-0030 threat model + 2 schválení.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — reaktivní PG spojení + Kafka producer.

Flyway má `connect-retries: 10` / interval `2s`, takže pod toleruje pomalou DB při startu.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,95 % (money-path / T0) | `up{service="ledger-service"}` |
| Latence p95 GET (journals/trial-balance) | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (zaúčtování) | < 300 ms | vč. validace vyvážení + insertu do outboxu |
| Zpoždění outboxu | < 10 s (tick dispatchu = 5 s) | stáří pending v `ledger_outbox` |
| Předvaha | vždy dává nulu | `GET /journals/trial-balance` `balanced=true` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Roste zpoždění outboxu

1. Spočítej PENDING: `SELECT count(*) FROM ledger_outbox WHERE status='PENDING'`.
2. Zkontroluj dostupnost Kafky a logy ticku dispatchu: `kubectl logs -l app=ledger-service | grep LedgerOutboxDispatcher`.
3. Invariant jediného zapisovatele: ledger Deployment **musí** zůstat `replicas: 1`. NEškáluj na víc replik kvůli vyčištění backlogu — to rozbije in-JVM `SKIP` garanci jediného nárokování (`FOR UPDATE SKIP LOCKED` je evidované zlepšení pro multi-writer).
4. Opakovaná selhání řádku posouvají řádek k DEAD (ohraničeno, ADR-0050 N5); zkontroluj `last_error` / `attempt_count`.

### Předvaha nedává nulu

Ber jako P1 incident integrity účetnictví. Per-měnové vyvažování (ADR-0025) je vynuceno při zaúčtování, takže nenulová předvaha ukazuje na korupci dat nebo chybu směrování partitionu. Zmraz účtování, pořiď snapshot knihy a rekonciliuj proti `balance-service` před jakýmkoli opravným zápisem. Opravy jsou pouze stornem — nikdy needituj zaúčtovaný zápis.

### Vynechaná / opakovaná FX revalvace

Denní běh automatizuje `FxRevaluationScheduler`. Opakování pro obchodní den: `POST /api/v1/ledger/fx-revaluation?date=YYYY-MM-DD` (operátor). Je idempotentní (`fx-reval-{date}`) — jeden zápis na den, opakování ve stejný den je no-op. Chybějící kurz ČNB pro měnu přeskočí jen daný řádek (logováno WARN).

### Horizont / retence partitionů

`JournalPartitionMaintainer` posouvá horizont automaticky. Ověř partitiony: `\d+ journal_entries`. Akce životního cyklu jsou v `partition_lifecycle_audit`. Neprázdný `journal_entries_default` (audit řádek DEFAULT_NONEMPTY) znamená, že roll-forward zaostal — prozkoumej, než zablokuje ATTACH nového partitionu.

## Matice verzí tech stacku

| Komponenta | Verze |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Jackson | 2.17.2 |
| Hibernate Reactive (Panache) | přes Quarkus BOM |
| SmallRye Reactive Messaging (Kafka) | přes Quarkus BOM |

## Deploy / release

Per-service path-scoped CI (buildují se jen změněné služby). Release osu vlastní **release-please** z Conventional Commits — needitovat ručně `version.txt`/`CHANGELOG.md` (aktuální release `1.2.0`). CD: ArgoCD sleduje tag image v deployment manifestu. U konfliktů tagu image v GitOpsu vždy `--ours`.
