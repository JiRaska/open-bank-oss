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
| `/api/v1/info` | 8104 | `ServiceInfoResource` (build metadata, z openbank-libs) |
| `/q/openbank/docs` | 8104 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8104 | OpenAPI spec |
| `/api/docs` | 8104 | Swagger UI |
| `/q/health` | 8104 | SmallRye Health (liveness + readiness) |

## Konfigurace

Služba se konfiguruje přes `application.yaml`. Dnes **nemá žádné externí závislosti na datastoru/brokeru/secretech**, takže konfigurační plocha je malá:

| Nastavení | Hodnota | Účel |
|---|---|---|
| `quarkus.http.port` | `8104` | port aplikace |
| `quarkus.http.cors.origins` | `localhost:3000`, `openbank-admin-ui:3000` | CORS allowlist |
| `quarkus.http.header.*` | bezpečnostní hlavičky | CSP, HSTS, X-Frame-Options, nosniff atd. |
| `quarkus.log.level` | `INFO` | úroveň logů |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI endpoint |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |

Nejsou potřeba žádné secrety, takže dnes neexistuje žádná blokující plocha Vault/`BootstrapVerifier` ([ADR 0017](../../../../docs/adr/0017-secrets-via-vault.md) platí teprve po zavedení credentialu k datastoru).

## Health checky

- **Liveness:** `/q/health/live` — JVM + ArC běží. (SmallRye Health je na classpath; žádné vlastní DB/broker readiness checky neexistují, protože takové závislosti zatím nejsou.)
- **Readiness:** `/q/health/ready` — proces připraven obsluhovat.

Až přijde MongoDB úložiště, měl by se přidat readiness check na připojení k datastoru.

## FinOps workload tier (ADR-0057)

Katalog jsou **bezstavová, převážně čtecí referenční data s nárazovým provozem** (procházení v admin UI, občasné čtení službami account/interest/fx/card). Je přirozeným kandidátem na **T1 — HTTP → 0**: scale-to-zero na příchozí HTTP přes KEDA HTTP add-on, tolerantní ke cold-startu v rámci svého latency SLO, ~0 idle náklady. Jako **non-money-path** služba smí škálovat na nulu. Dle [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md) je tier **odvozen z naměřeného provozu**, ne přiřazen ručně; zde uvedená hodnota je očekávaná klasifikace, kterou potvrdí FinOps klasifikátor.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Poznámky |
|---|---|---|
| Dostupnost | 99,5 % | služba referenčních dat, ne money-path |
| Latence p95 GET | < 50 ms | in-memory čtení, bez I/O |
| Cold start (T1 scale-to-zero) | v rámci latency SLO | Quarkus fast-jar startuje v desítkách ms |
| Chybovost | < 0,1 % 5xx | |

## Runbooky

- **Zastaralé nebo špatné ceny v admin UI** — UI musí číst `GET /api/v1/fees`; ověř, že nespadá na napevno zadrátovaný seznam. Zkontroluj `fees[]` a `status` produktu přes `GET /api/v1/products/{id}`.
- **Produkt chybí ve veřejném seznamu** — zkontroluj `status == ACTIVE` a `isPublic == true`; produkty DRAFT/INACTIVE/neveřejné jsou vyloučeny ze zákaznických pohledů.
- **Stav ztracen po restartu** — dnes očekávané: úložiště je in-memory a při každém bootu znovu naseeduje pevný 15-produktový katalog. Jakýkoli runtime create/update se neperzistuje, dokud nepřijde DB úložiště ([04 — Data](./04-data.md)).
- **Duplicitní code při create** — `409`; zvol unikátní `code`.

## Release

Vydávaný komponent (má `version.txt`, aktuálně `0.1.0`). Verzování/changelog vlastní release-please z Conventional Commits ([ADR 0029](../../../../docs/adr/0029-versioning-release-and-governance-as-code.md)). Neupravuj `version.txt` ručně ve feature PR. Změny API kontraktu zvedají `openapi.yaml:info.version` nezávisle ([ADR 0048](../../../../docs/adr/0048-decouple-api-contract-version-from-service-release-version.md)).
