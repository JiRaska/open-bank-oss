# Provoz

## Build & běh

```bash
# Build (lokálně)
./gradlew :openbank-product-catalog:quarkusBuild

# Dev režim (live reload)
./gradlew :openbank-product-catalog:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build
```

### Kontejnerový image

`:openbank-product-catalog:quarkusBuild` (fast-jar) běží na hostu z **plného kořenového kontextu repa** (kořenový `settings.gradle.kts` `include`uje každý modul) a `.github/workflows/Dockerfile.deploy` zkopíruje výsledný `quarkus-app/` do image `eclipse-temurin:25-jre` (glibc, #3354). Běží pod non-root uživatelem `openbank`, `EXPOSE`uje 8104 a startuje se ZGC. `openbank-product-catalog/Dockerfile` nic nestaví (#3016) — pipeline z něj čte jedinou věc, řádek `EXPOSE`.

> Platformní pravidlo: **vždy fast-jar, nikdy uber-jar** — runtime stage kopíruje `quarkus-app/`. Builduj na hostiteli, ne in-Docker Gradle. Generický helper: `openbank-infra/scripts/build-push-service.sh openbank-product-catalog`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/products/...` | 8104 | REST API produktového masteru |
| `/api/v1/fees` | 8104 | celobankovní sazebník |
| `/api/v2/product-types/...` | 8104 | důvěryhodná schémata a jejich validace |
| `/api/v2/specifications`, `/offerings` | 8104 | generický produktový master a nabídky |
| `/api/v2/offerings/{id}/revisions...` | 8104 | autorský a maker-checker publikační tok |
| `/api/v2/products/{id}` | 8104 | pouze publikované kontextové čtení |
| `/api/v2/events` | 8104 | trvalý kurzor změn pro standalone integrace |
| `/api/v1/info` | 8104 | `ServiceInfoResource` (build metadata, z openbank-libs) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/api/docs` | 8085 | Swagger UI |
| `/q/health` | 8085 | SmallRye Health (liveness + readiness, management port) |

## Konfigurace

Služba se konfiguruje přes `application.yaml`. Bankovní profil vyžaduje PostgreSQL a OIDC; Kafka ani Redis dnes zapojené nejsou:

| Nastavení | Hodnota | Účel |
|---|---|---|
| `quarkus.http.port` | `8104` | port aplikace |
| `quarkus.management.port` | `8085` | port health a metrik |
| `openbank.api.version` | `2` | nejnovější major; response hlavička zohledňuje cestu v1/v2 |
| `REACTIVE_URL` / `JDBC_URL` | lokální PostgreSQL defaulty | databázová URL aplikace a Flyway |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | lokální vývojové defaulty | databázové přihlašovací údaje |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | lokální OpenBank realm | OIDC issuer/discovery URL |
| `CATALOG_SCOPE_CLAIM` | `scope` | JWT claim se scopes |
| `CATALOG_READ_SCOPE` / `CATALOG_AUTHOR_SCOPE` / `CATALOG_PUBLISH_SCOPE` | `catalog:*` | provider-neutral mapování oprávnění |
| `OPENBANK_CATALOG_PACKS` | bank: `banking,insurance`; standalone: prázdné | explicitní výběr důvěryhodných packů |
| `OPENBANK_BANK_V1_COMPATIBILITY_ENABLED` | bank: `true`; standalone: `false` | zapnutí legacy bankovního API, seedů a projekce |
| `quarkus.http.cors.origins` | `localhost:3000`, `openbank-admin-ui:3000` | CORS allowlist |
| `quarkus.http.header.*` | bezpečnostní hlavičky | CSP, HSTS, X-Frame-Options, nosniff atd. |
| `quarkus.log.level` | `INFO` | úroveň logů |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI endpoint |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |

Produkční databázové přihlašovací údaje jsou secrety a musí je dodat deployment; hodnota v repozitáři je jen lokální vývojový default.
Standalone při startu odmítá plaintext OIDC issuer. Legacy `/api/v1` navíc vyžaduje banking pack i
explicitně zapnutý compatibility flag.

## Health checky

- **Liveness:** `:8085/q/health/live` — JVM + ArC běží.
- **Readiness:** `:8085/q/health/ready` — zahrnuje extension-provided readiness datového zdroje.

## FinOps workload tier (ADR-0057)

Katalog je **stavová služba s bezstavovými aplikačními replikami**: trvalý stav vlastní PostgreSQL. Aplikace je teoreticky vhodná pro HTTP scale-to-zero, ale přímí bankovní volající dnes drží minimum na jedné replice; tuto provozní hranici zachycuje ADR-0083. Změna se musí opřít o měření provozu a chování závislostí.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Poznámky |
|---|---|---|
| Dostupnost | 99,5 % | služba referenčních dat, ne money-path |
| Latence p95 GET | < 50 ms | návrhový cíl včetně PostgreSQL I/O |
| Cold start | v rámci timeoutů volajících | musí se měřit přes KEDA proxy cestu |
| Chybovost | < 0,1 % 5xx | |

## Runbooky

- **Zastaralé nebo špatné ceny v admin UI** — UI musí číst `GET /api/v1/fees`; ověř, že nespadá na napevno zadrátovaný seznam. Zkontroluj `fees[]` a `status` produktu přes `GET /api/v1/products/{id}`.
- **Požadován zákaznický seznam** — ve v1 veřejná projekce neexistuje. Nevystavuj autentizovaný operátorský seznam; použij až publikovanou projekci v2.
- **Stav chybí po restartu** — jde o incident: stav je v PostgreSQL. Ověř cílovou databázi, historii Flyway a obnovu; seeder nikdy nepřepisuje neprázdné úložiště.
- **Duplicitní code při create** — `409`; zvol unikátní `code`.
- **Standalone consumer zaostává** — pokračuj na `GET /api/v2/events` od posledního potvrzeného
  opaque cursoru; zpracování musí být idempotentní podle event `id`.
- **Rollback na starší v1 binárku změnil bankovní produkt** — při návratu aktuální verze porovná
  startup reconciler a následně každých 30 sekund `products.row_version` s uloženým watermarkem a
  vytvoří/obnoví v2 draft. Oboustrannou změnu odmítne jako konflikt; řeš ji přes normální
  author/publish tok a neupravuj audit ani outbox ručně.

## Release

Vydávaný komponent (má `version.txt`). Verzování/changelog vlastní release-please z Conventional Commits ([ADR 0029](../../../../docs/adr/0029-versioning-release-and-governance-as-code.md)). Neupravuj `version.txt` ručně ve feature PR. Změny API kontraktu zvedají `openapi.yaml:info.version` nezávisle ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
