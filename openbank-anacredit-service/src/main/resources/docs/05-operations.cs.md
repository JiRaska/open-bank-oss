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

Z `application.yaml` — minimální plocha (ve v1 žádné DB / Kafka / Redis env):

| Nastavení | Hodnota | Účel |
|---|---|---|
| `quarkus.http.port` | `8137` | port app + management |
| `quarkus.http.cors.origins` | `http://localhost:3000,http://openbank-admin-ui:3000` | allow-list originů admin UI |
| security hlavičky | `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS atd. | zpevněné response hlavičky |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI dokument |

Auth (Keycloak OIDC issuer, realm) a případná budoucí nastavení datasource dodává deploy prostředí / defaulty `openbank-libs`, nejsou natvrdo v `application.yaml`.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — služba připravena obsluhovat. v1 nemá závislost na externím úložišti, takže readiness negatuje na DB/Kafka pool.

> **Provozní poznámka:** in-memory store je netrvanlivý — restart podu ztratí všechny registrované expozice, které je nutné znovu naplnit před vykreslením dalšího výkazu. To je pro dávkové použití ve v1 přijatelné a odstraní to plánovaná PostgreSQL persistence.

## FinOps workload tier (ADR-0057)

| Osa | Posouzení |
|---|---|
| Money-path / mandatorně kontinuální? | **Ne** — není v `rules.yaml: money_path_services`; derive-only |
| Trigger | synchronní HTTP request/response (žádný rezidentní posluchač, žádný Kafka consumer) |
| Tvar provozu | vzácný / nárazový — expozice se vkládají a výkazy vykreslují kolem měsíčních referenčních dat |
| Tolerance cold-startu | vysoká — regulatorní vykreslení není latency-kritické |

⇒ **Tier T1 — HTTP → 0** (škálování od/do nuly na příchozí HTTP přes KEDA HTTP add-on). Klidový náklad ≈ 0. Netrvanlivý in-memory store znamená, že každý cold start začíná prázdný; upstream feed znovu zaregistruje expozice před vyžádáním výkazu. Tier je *odvozen z naměřeného provozu*, není přiřazen ručně (ADR-0057), takže jde o doporučenou klasifikaci, podléhající CI bráně declared-vs-measured.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Poznámka |
|---|---|---|
| Dostupnost | best-effort (T1, ne T0) | scale-to-zero tolerováno; bez mandátu kontinuální služby |
| Latence p95 (vykreslení výkazu) | < 200 ms warm | čistá in-memory agregace nad množinou expozic |
| Cold-start | v rámci budgetu HTTP add-onu | Quarkus fast-jar startuje v desítkách až stovkách ms |
| Chybovost | < 0,1 % 5xx | neočekávané chyby nesou correlation id přes libs |

## Runbooky

### Výkaz vypadá prázdný / podhodnocený po deployi

1. Store ve v1 je in-memory a **ztratí se při restartu**. Ověř čerstvý pod: `kubectl get pod -l app=anacredit-service -o wide`.
2. Znovu spusť feed expozic (re-POST expozice) před vykreslením výkazu.
3. Ověř přes `GET /api/v1/anacredit/exposures`, že očekávané nástroje jsou přítomné.

### Nástroj nečekaně vyloučen

1. Vykresli výkaz a přečti jeho stopu `exclusions` — každé vyřazení má `reason`.
2. `HOUSEHOLD_OUT_OF_SCOPE` ⇒ `debtorType` je `NATURAL_PERSON` (správně: AnaCredit jen právnické osoby).
3. `BELOW_THRESHOLD` ⇒ **agregovaný** `committedAmountEur` dlužníka napříč všemi nástroji je `< €25 000`; zkontroluj dodanou EUR částku (FX sourcing je věc volajícího).
4. `NO_EXPOSURE` ⇒ committed i drawn jsou nula.

### 403 na každém volání

Resource vyžaduje jednu z rolí `ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_SERVICE`. Zkontroluj realm role tokenu.

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
