# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar — nikdy uber-jar)
./gradlew :openbank-customer-edge:quarkusBuild -Dquarkus.package.jar.type=fast-jar

# Dev mode (live reload)
./gradlew :openbank-customer-edge:quarkusDev

# Kontejnerový image (z openbank-infra/)
openbank-infra/scripts/build-push-service.sh customer-edge
```

Image staví `.github/workflows/Dockerfile.deploy` z fast-jaru sestaveného na hostu — runtime base `eclipse-temurin:25-jre` (glibc, #3354), non-root uživatel `openbank`, `-XX:+UseZGC`, port 8128. `openbank-customer-edge/Dockerfile` nic nestaví (#3016); pipeline z něj čte jedinou věc, řádek `EXPOSE`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/customer/v1/...` | 8128 | zákaznické REST API (proxy) |
| `/api/v1/info` | 8128 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8128 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8128 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (management port) |

Pozor na **oddělený management port 8085** (`quarkus.management.port`), aby probey a metriky nebyly exponovány na internet-facing 8128.

## Konfigurace

| Property / env var | Default | Účel |
|---|---|---|
| `quarkus.http.port` | `8128` | app port |
| `quarkus.management.port` | `8085` | health/metrics port |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://keycloak.iam.svc:8080/realms/openbank-customers` | validace příchozího JWT (fetch JWKS) |
| `OIDC_CLIENT_ID` | `openbank-edge` | příchozí OIDC client id |
| `QUARKUS_OIDC_TOKEN_ISSUER` | `https://kc.open-bank.tech/realms/openbank-customers` | **připnutý veřejný issuer** zákaznických tokenů |
| `openbank.upstream.token-url` | `http://keycloak.iam.svc:8080/realms/openbank` | token endpoint operátorského realmu (M2M) |
| `openbank.upstream.client-id` | `openbank-edge` | M2M client id |
| `OPENBANK_UPSTREAM_CLIENT_SECRET` | *(žádný)* | **M2M client secret — dodán za běhu (Vault); musí být nastaven, jinak každé upstream volání 401→502** |
| `openbank.upstream.connect-timeout-ms` | `5000` | connect timeout upstreamu |
| `openbank.upstream.request-timeout-ms` | `10000` | per-request timeout upstreamu |
| `ACCOUNT_SERVICE_URL` … `STATEMENT_SERVICE_URL` | in-cluster DNS | báze URL upstreamů (jedna na proxovanou službu) |

> **Past (ze zdrojových komentářů):** **nedeklarujte** `client-secret` v `application.yaml` s expanzí `${UPSTREAM_OIDC_CLIENT_SECRET:}` — název property ve formě ENV se rozlišuje nespolehlivě a tiše vrátí `""`, čímž každý fetch tokenu skončí 401. Dodejte ho přes env var `OPENBANK_UPSTREAM_CLIENT_SECRET` (standardní mapování ENV→dotted-property).

## Health checky

- **Liveness:** `/q/health/live` (port 8085) — běží JVM + ArC.
- **Readiness:** `/q/health/ready` (port 8085) — SmallRye Health. Jelikož je edge bezstavový, nemá DB/Kafka readiness bránu; dosažitelnost Keycloaku/upstreamů se sleduje při requestu (degraduje na 401/502), ne selháním readiness.

## FinOps workload tier (ADR-0057)

Edge je **bezstavová HTTP request/response služba** — kanonický kandidát na **Tier T1 (HTTP → 0)**: škálovat z/na nulu podle příchozího HTTP (KEDA HTTP add-on / Knative), protože Quarkus fast-jar startuje za studena v desítkách milisekund. **Není** to money-path always-on (T0) služba (viz [06 — Compliance](./06-compliance.md)). Dle ADR-0057 se tier odvozuje z naměřeného provozu, ne přiřazuje ručně; T1 je default pro čistou HTTP edge.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="customer-edge"}` |
| Latence p95 GET (proxované čtení) | < 250 ms | `http_server_requests_seconds{quantile=0.95}` (zahrnuje jeden upstream hop) |
| Latence p95 POST (iniciace platby, obohacená) | < 600 ms | zahrnuje 1–2 upstream resolution hopy + volání initiate |
| Chybovost | < 0,5 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` (502 = transport upstreamu) |

## Runbooky

### Všechna upstream volání vrací 502

Symptom: každé proxované volání vrací `{"error":"upstream unavailable"}`.

1. **Nejdřív zkontroluj M2M secret** — chybějící/prázdný `OPENBANK_UPSTREAM_CLIENT_SECRET` způsobí, že `client_credentials` vrátí 401, což edge vyhodí jako 502. Ověř, že env var/secret je namountován.
2. Zkontroluj dosažitelnost token endpointu: `openbank.upstream.token-url` (operátorský realm) se resolvuje a odpovídá 200 na `client_credentials` POST.
3. Zkontroluj DNS/dosažitelnost upstreamu pro dotčenou `*_SERVICE_URL`.
4. Prohlédni logy: `kubectl logs -l app=customer-edge | grep "upstream call to"` — loguje se selhávající URL + třída výjimky.

### 401 na každé autentizované cestě

1. Ověř, že příchozí token je z realmu `openbank-customers` a není expirovaný.
2. Ověř, že `QUARKUS_OIDC_TOKEN_ISSUER` odpovídá `iss` tokenu (veřejný KC host) — issuer je připnutý nezávisle na URL pro fetch JWKS.
3. Ověř, že JWKS je dosažitelné na `QUARKUS_OIDC_AUTH_SERVER_URL` (in-cluster KC).

### Veřejná onboarding cesta vrací 401

`POST /onboarding/start` musí být anonymní. Pokud vrací 401, ověř `quarkus.http.auth.proactive=false` a že cestu obsluhuje `OnboardingResource` (bez třídního `@RolesAllowed`), ne `CustomerEdgeResource`.

### 403 na čtení, které zákazník očekává jako své

Toto je IDOR ochrana fungující dle návrhu: `accountId` se neresolvoval na party z JWT. Ověř, že účet patří volající party v account-service; vrací se 403 (ne 404) záměrně, aby se předešlo existenčnímu oraclu.

## Deploy / release

- Per-service path-scoped CI buildí jen při změně `openbank-customer-edge/src/main/**`; verzi spravuje release-please (`version.txt` = `0.9.0`; ručně needitovat).
- Build image → push do registry; ArgoCD vyzvedne nový tag.
- Zákaznický ingress aplikuje rate limit per IP (`limit-rps`) jako první linii obrany proti zneužití (ADR-0065 / ADR-0069); hlubší bot/spam hardening na `/onboarding/start` je follow-up Fáze 2 ADR-0069.

## Tech stack

| Komponenta | Verze |
|---|---|
| Kotlin | dle `libs.versions.toml` (fleet-wide) |
| Quarkus | 3.x (RESTEasy Reactive, OIDC, SmallRye Health/OpenAPI, config-yaml) |
| JDK runtime | 25 (Eclipse Temurin, ZGC) |
| HTTP klient (odchozí) | JDK `java.net.http.HttpClient` (HTTP/1.1) |

Přesně připnuté verze jsou za běhu v payloadu `/api/v1/info`.
