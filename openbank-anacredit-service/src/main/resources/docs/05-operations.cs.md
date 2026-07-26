# Provoz

## Build & běh

```bash
# Build (fast-jar)
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:quarkusBuild

# Dev mód (live reload)
./gradlew :openbank-anacredit-service:quarkusDev

# Testy (doménové testy jsou čisté JUnit; AnaCreditResourceTest je @QuarkusTest)
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:test --offline
```

Buildy image používají **fast-jar** (`-Dquarkus.package.jar.type=fast-jar`); runtime stage kopíruje `quarkus-app/`. Generický build: `openbank-infra/scripts/build-push-service.sh anacredit-service`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/anacredit/exposures` | 8137 | registrace / výpis expozic |
| `/api/v1/anacredit/returns/{referenceDate}` | 8137 | vykreslení výkazu AnaCredit |
| `/api/v1/info` | 8137 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8137 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8137 | OpenAPI spec |
| `/api/docs` | 8137 | Swagger UI |
| `/q/health` | 8137 | liveness + readiness (SmallRye Health) |

App i management sdílí port **8137** (žádný samostatný management port).

## Konfigurace

Z `application.yaml` (ADR-0037 v2 přidává datasource PostgreSQL + Flyway; žádná Kafka / Redis):

| Nastavení | Hodnota | Účel |
|---|---|---|
| `quarkus.http.port` | `8137` | port app + management |
| `quarkus.http.cors.origins` | `http://localhost:3000,http://openbank-admin-ui:3000` | allow-list originů admin UI |
| security hlavičky | `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS atd. | zpevněné response hlavičky |
| `quarkus.datasource.reactive.url` / `.jdbc.url` | `openbank_anacredit` (localhost default) | reaktivní Panache provoz aplikace / Flyway migrace |
| `quarkus.flyway.migrate-at-start` | `true` | schéma aplikováno při startu |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI dokument |

Auth (Keycloak OIDC issuer, realm) a přihlašovací údaje datasource dodává deploy prostředí, nejsou natvrdo v `application.yaml`.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — služba připravena obsluhovat. Od ADR-0037 v2 readiness nyní závisí na dostupnosti reaktivního connection poolu Postgres (vestavěný datasource check SmallRye Health).

## FinOps workload tier (ADR-0057)

| Osa | Posouzení |
|---|---|
| Money-path / mandatorně kontinuální? | **Ne** — není v `rules.yaml: money_path_services`; derive-only |
| Trigger | synchronní HTTP request/response (žádný rezidentní posluchač, žádný Kafka consumer) |
| Tvar provozu | vzácný / nárazový — expozice se vkládají a výkazy vykreslují kolem měsíčních referenčních dat |
| Tolerance cold-startu | vysoká — regulatorní vykreslení není latency-kritické |

⇒ **Tier T1 — HTTP → 0** (škálování od/do nuly na příchozí HTTP přes KEDA HTTP add-on). Klidový výpočetní náklad ≈ 0; instance Postgres `openbank_anacredit` samotná je nyní malá trvale běžící nákladová položka (dříve nulová u in-memory storu v1) — expozice zaregistrované před scale-to-zero nyní **přežijí** další cold start (to je smysl ADR-0037 v2). Cold start navíc potřebuje živé připojení k reaktivnímu poolu, než projde readiness. Tier je *odvozen z naměřeného provozu*, není přiřazen ručně (ADR-0057), takže jde o doporučenou klasifikaci, podléhající CI bráně declared-vs-measured.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Poznámka |
|---|---|---|
| Dostupnost | best-effort (T1, ne T0) | scale-to-zero tolerováno; bez mandátu kontinuální služby |
| Latence p95 (vykreslení výkazu) | < 200 ms warm | reaktivní Panache dotaz nad `credit_exposures` (indexováno na `debtor_id`) |
| Cold-start | v rámci budgetu HTTP add-onu | Quarkus fast-jar startuje v desítkách až stovkách ms; plus handshake Postgres poolu |
| Chybovost | < 0,1 % 5xx | neočekávané chyby nesou correlation id přes libs |

## Runbooky

### Výkaz vypadá prázdný / podhodnocený po deployi

1. Expozice jsou nyní trvanlivé (ADR-0037 v2) — samotný restart podu by je **neměl** ztratit. Pokud je výkaz prázdný, nejprve ověř, že se Flyway migrace skutečně aplikovala: `SELECT * FROM flyway_schema_history;` by měla ukázat `V2__create_credit_exposures` jako `success`.
2. Pokud migrace chybí nebo je tabulka skutečně prázdná, znovu spusť feed expozic (re-POST expozice) před vykreslením výkazu.
3. Ověř přes `GET /api/v1/anacredit/exposures`, že očekávané nástroje jsou přítomné.

### Nástroj nečekaně vyloučen

1. Vykresli výkaz a přečti jeho stopu `exclusions` — každé vyřazení má `reason`.
2. `HOUSEHOLD_OUT_OF_SCOPE` ⇒ `debtorType` je `NATURAL_PERSON` (správně: AnaCredit jen právnické osoby).
3. `BELOW_THRESHOLD` ⇒ **agregovaný** `committedAmountEur` dlužníka napříč všemi nástroji je `< €25 000`; zkontroluj dodanou EUR částku (FX sourcing je věc volajícího).
4. `NO_EXPOSURE` ⇒ committed i drawn jsou nula.

### 403 na každém volání

Resource vyžaduje jednu z rolí `ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_API`. Zkontroluj realm role tokenu.

## Matice verzí tech stacku

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x (platform `libs.versions.toml`) |
| Quarkus | 3.x (enforced BOM) |
| JDK runtime | 25 (Eclipse Temurin) |
| Jackson | přes Quarkus BOM (`jackson-module-kotlin`, `jackson-datatype-jsr310`) |

Přesné připnuté verze jsou auto-generovány z `libs.versions.toml` při buildu a vystaveny v payloadu `/api/v1/info`.

## Deploy / release

- Per-service path-scoped CI builduje jen při změně souborů pod `openbank-anacredit-service/src/main/**`.
- `version.txt` vlastní **release-please** (per-service komponenta); feature/fix PR jej ručně neupravují.
- Verze OpenAPI kontraktu (`openapi.yaml:info.version`) je samostatná osa API-kontraktu (ADR-0048) a je ověřována `AnaCreditContractTest`.
- CD: ArgoCD vyzvedne nový image tag z bumpu gitops manifestu.
