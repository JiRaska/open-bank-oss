# Provoz

## Build & běh

```bash
# Build (lokálně) — fast-jar (nikdy uber-jar)
./gradlew :openbank-interest-service:quarkusBuild

# Dev mód (live reload; OIDC vypnut v %dev)
./gradlew :openbank-interest-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build
```

Build image je host-side fast-jar (`-Dquarkus.package.jar.type=fast-jar`); runtime stage COPYuje `quarkus-app/`. Generický build: `openbank-infra/scripts/build-push-service.sh interest-service`.

## Endpointy & porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/interest/...` | 8125 | byznysové REST API |
| `/api/v1/interest/withholding/remittances/...` | 8125 | API odvodu srážkové daně |
| `/api/v1/info` | 8125 | ServiceInfoResource (build + verze) |
| `/api/docs` | 8125 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Management endpointy běží na odděleném management portu **8085** (`quarkus.management.enabled`, root-path `/q`). App HTTP port je **8125**.

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ být přepsáno přes Vault (ADR-0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **v prod přepsat přes Vault** |
| (datasource reactive URL) | `postgresql://localhost:5432/openbank_interest` | připojení DB |
| (OIDC auth-server-url) | `http://localhost:8080/realms/openbank` | OIDC issuer |
| (Redis hosts) | `redis://localhost:6379` | Valkey (zapojeno; ve v1 bez idempotency-key toku) |
| (OTEL endpoint) | `http://localhost:4317` | OpenTelemetry OTLP |

Naplánované úlohy (konfigurační klíče pod `openbank.interest`):

| Klíč | Default | Účel |
|---|---|---|
| `accrual-cron` | `0 0 1 * * ?` | denní accrual tick (01:00) |
| `capitalization-cron` | `0 0 2 1 * ?` | měsíční kapitalizační tick (02:00 prvního dne) |
| `day-count-convention` | `ACT_365` | výchozí počítání dní, není-li na konfiguraci |
| `openbank.outbox.poll-interval` | `5s` | tick outbox dispatcheru |

Placeholder tajemství jsou jen pro dev; produkce musí injektovat reálné hodnoty přes Vault (ADR-0017).

Všechna odchozí volání mezi službami (ledger, transaction-service, account-service, party-service a od #8481 i čtení produktového katalogu) se autentizují pojmenovaným oidc-clientem `ledger`: Keycloak klient `openbank-interest` s vlastním secretem `OIDC_LEDGER_CLIENT_SECRET`, nikdy sdílený principal `openbank-services`. Čtení produktového katalogu má pro `service-account-openbank-interest` v `account_rest_ext.rego` oprávnění `catalog.read` (pouze publikovaná verze); sdílený klient s `OIDC_CLIENT_SECRET` zůstává nakonfigurovaný jako výchozí, ale žádný rest-client jej nevybírá.

## Health checky

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC běží.
- **Readiness:** `/q/health/ready` (port 8085) — reaktivní DB pool + Kafka producer.

Flyway běží `migrate-at-start` s `connect-retries: 10` (interval 2s), takže pod při studeném startu počká na DB.

## FinOps workload tier (ADR-0057)

`interest-service` **není** money-path služba (není v `rules.yaml: money_path_services`), a proto **není T0** ve výchozím stavu. Jeho workload je směs:

- **Asynchronní outbox dispatcher** (Kafka publisher) — kandidát **T2** (event → 0) (KEDA na consumer-group lag / backlog outboxu).
- **Periodický scheduler** (accrual/kapitalizační cron) — tvar **T3** (periodicky, bez listeneru); rozvrh musí workload probudit.
- **HTTP read/admin plocha** — kandidát **T1** (HTTP → 0), tolerující cold-start v rámci SLO.

Dle ADR-0057 je tier **odvozen z měřeného chování**, ne přiřazen ručně; default pro novou ne-money-path službu je nejnižší tier, který její spouštěč dovolí. **Výhrada:** outbox dispatcher pinuje Deployment na `replicas: 1` (garance jednoho writeru, ADR-0050) a cron úlohy musí střílet dle rozvrhu — obojí omezuje, jak agresivně může workload škálovat na nulu. Před přepnutím scale-to-zero sladit deklarovaný tier s invarianty dispatcheru/scheduleru.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,5 % (admin/dávková plocha) | Prometheus `up{service="interest-service"}` |
| Latence p95 GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 kapitalizace | < 500 ms | zahrnuje resolve daňového profilu + DB zápisy + insert outboxu |
| Lag outboxu | < 30 s | stáří pending na `interest_outbox` |
| Error rate | < 0,5 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

(Cíle jsou dokumentační default; ověřte proti platformovému SLO registru — TBD.)

## Runbooky

### Roste lag outboxu

1. Spočítejte pending: `SELECT count(*) FROM interest_outbox WHERE status='PENDING'`.
2. Zkontrolujte dosažitelnost Kafky a topic `openbank.interest.accrual.event`.
3. Zkontrolujte logy dispatcheru: `kubectl logs -l app=interest-service | grep InterestOutboxDispatcher`.
4. Publisher má circuit breaker (`failureRatio 0.5`, `delay 5000ms`); opakovaná selhání směřují řádek k přechodu DEAD (ADR-0050 N5). Prozkoumejte `last_error` / `attempt_count`.

### Kapitalizace selže s „No active rate config"

Příčina: žádný řádek `interest_rate_configs` aktivní pro produkt k `toDate`. Akce: vytvořte/aktivujte konfiguraci sazby přes `POST /api/v1/interest/rates` pokrývající období.

### Srážka vypadá špatně (daň 0 kde se čeká, nebo naopak)

1. Ověřte měnu úroku — ve v1 se sráží jen **CZK**; ne-CZK je `DEFERRED_FX` (daň 0).
2. Ověřte profil příjemce — v1 vždy resolvuje na fail-safe CZ rezidenta fyzickou osobu (15 %); cesty právnická osoba / smlouva / osvobození vyžadují fast-follow resoluce party daní.
3. Prozkoumejte řádek `withholding_tax`: `treatment`, `rate`, `taxable_base`, `tax_amount`.

### Odvod sestaven, ale hotovost neodešla

Záměrně: `interest-service` nepřesouvá hotovost. Událost `interest.withholding.remitted.v1` deleguje odvod na downstream daňový/reporting konzument, který dávku přepne na `SETTLED`. Kontrolujte konzumenta, ne tuto službu.

## Matice tech stacku

Automaticky vystaveno v `/api/v1/info` (z `libs.versions.toml`):

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x (platformový pin) |
| Quarkus | 3.x LTS |
| JDK runtime | dle platformy (Temurin) |
| PostgreSQL | 16 (reaktivní `pg-client` + JDBC pro Flyway) |
| Kafka client | SmallRye Reactive Messaging |

## Deploy / release

- Per-service path-scoped CI: test (unit + Testcontainers PostgreSQL/Valkey na JVM, in-memory Kafka), `quarkusBuild` fast-jar, SBOM, build/push image, ArgoCD zvedne tag.
- **Release osa** (release-please) je řízena `version.txt` + Conventional Commits; **osa API kontraktu** je `openapi.yaml: info.version` (ADR-0048). Nevynucujte jejich rovnost.
- GitOps: u konfliktů image-tagů berte `--ours` (čerstvě sestavený tag); u RBAC/config berte `--theirs` nebo řešte ručně.

## Účast na závěrce dne (ADR-0342)

Interest hlásí ledgeru jako účastník `interest`. Obchodní datum každého běhu připsání i kapitalizace je účetní den ledgeru (`Europe/Prague`, přes `AccountingClock`), nikoli kalendářní datum UTC; běh mezi 00:00 a 02:00 pražského času se proto zapíše pod den, který ledger skutečně uzavírá. Běh kapitalizace, ve kterém byl některý pár odmítnut (409 uzavřeného dne) nebo selhal z jiného důvodu, se eviduje jako neúplný a hlásí se jako `REFUSED` s počty a pevnými kategoriemi selhání; jako `RAN` se hlásí jen plně kapitalizovaný běh. Totéž platí pro připsání: účet, jehož připsání selhalo nebo jehož zůstatek account-service nedodal, způsobí `REFUSED`, stejně jako zaseknutý claim kapitalizace, který se obnovou nepodařilo dokončit. Den ve stavu `REFUSED` nebo s chybou se sám znovu nespustí; zbývá jen opakovat běh pro STEJNÉ pražské obchodní datum (ruční `POST /accrue/all` a `/capitalize` toto datum používají jako výchozí a jsou idempotentní na účet a den) nebo waiver operátora na stránce uzávěrky dne v ledgeru. `openbank_workflow_last_success_age_seconds{workflow="interest-close-participation"}` se obnoví jen tehdy, když ledger hlášení z daného běhu přijal, takže nedostupný ledger nebo trvalé 403/404 nechá zazvonit alert na zastaralost.

Automatické doklady denní uzávěrky a jejich nová souhrnná varování obsahují pouze obchodní datum, počty a pevné kategorie selhání včetně příznaku chyby seznamu účtů. Zákaznické identifikátory, identifikátory účtů či produktů ani původní text výjimky nebo odmítnutí se do tohoto nového toku hlášení nekopírují. Nejde o tvrzení, že jsou anonymizovány všechny starší logy služby. Důvod výjimky a identita operátora v ledgeru zůstávají záměrnými auditními osobními údaji; do důvodu výjimky nepatří zákaznické podrobnosti. Výchozí režim zůstává shadow a změna nepovoluje aktivaci.

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
