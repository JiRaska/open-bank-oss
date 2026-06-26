# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar — nikdy uber-jar)
./gradlew :openbank-fx-service:quarkusBuild

# Dev mód (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-fx-service:quarkusDev

# Generický build/push image (build na hostu, ne in-Docker Gradle)
openbank-infra/scripts/build-push-service.sh fx-service
```

Lokální brána před PR: `./gradlew detekt ktlintCheck koverVerify build`. Kover line floor je **40 %** (money-path baseline dle `rules.yaml`; aspirační cíl 70 %).

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/fx/...` | 8119 | byznys REST API |
| `/api/v1/fx/cnb/...` | 8119 | ingest/čtení ČNB fixingu |
| `/api/v1/info` | 8119 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8119 | Swagger UI |
| `/q/openapi` | 8119 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace, management port) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je na **portu 8085**, root-path `/q` (`quarkus.management`). Zapojeny jsou SmallRye Health, Micrometer/Prometheus a OpenTelemetry (OTLP → `:4317`).

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB heslo — **v prod nutno přepsat přes Vault (ADR 0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `CNB_FEED_URL` | `https://www.cnb.cz/.../denni_kurz.txt` | feed ČNB fixingu |
| `CNB_CURRENCIES` | `EUR,USD,GBP` | měny ingestnuté z ČNB fixingu |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sankční screening |
| `AML_SERVICE_URL` | `http://localhost:8117` | úložiště AML případů |

Datasource: `postgresql://localhost:5432/openbank_fx`. Kafka bootstrap: `localhost:29092`. OIDC issuer: `http://localhost:8080/realms/openbank`, klient `openbank-services`. OIDC je **vypnuto** v `%dev` a `%test`. Rate limit: `openbank.rate-limit` (max 200 souběžných requestů). Outbox poll: 5s. Bezpečnostní response hlavičky (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY atd.) jsou nastaveny v `application.yaml`.

## Serverless tier (ADR-0057)

`fx-service` je **money-path** služba (`rules.yaml: money_path_services`). Volání `POST /convert` je **synchronní peněžní skok** se synchronním downstream voláním screeningu, takže latence cold-startu je nepřijatelná → **Tier T0 — Always-on** (`minReplicas ≥ 1`, nikdy neškáluje na nulu), s PodDisruptionBudget pro krytí dobrovolných výpadků. Sestup pod T0 vyžaduje threat model ADR-0030 + 2 schválení (T0 je pro money-path „posvátný").

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC. Restart podu při selhání.
- **Readiness:** `/q/health/ready` (port 8085) — DB pool + Kafka producer + konfigurace downstream klientů.

Flyway má `connect-retries: 10` / `connect-retries-interval: 2S`, takže pod toleruje DB ještě-nepřipravenou při bootu.

## SLO

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="openbank-fx-service"}` |
| Latence p95 GET kurz | < 100 ms | `http_server_requests_seconds` |
| Latence p95 POST convert | < 500 ms | zahrnuje synchronní sankční screen |
| Outbox lag | < 30 s | stáří PENDING řádku (poll 5s, batch 25) |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Konverze uvízlé v PENDING

PENDING znamená, že screening řekl REVIEW nebo byla sanctions-service nedostupná.
1. Zkontroluj zdraví sanctions-service: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. Zkontroluj logy fx-service na `SCREENING_UNAVAILABLE` / `AML_HOLD`.
3. Pro každou PENDING konverzi by měl existovat AML případ v `aml-service` — řešení jde přes životní cyklus AML případu, ne opětovným POST convert (idempotency key vrátí jen PENDING záznam).

### Rostoucí outbox lag

1. `SELECT count(*) FROM fx_outbox WHERE status='PENDING'`.
2. Zkontroluj dostupnost Kafky a stav circuit breakeru dispatcheru v logu (`FxOutboxDispatcher`).
3. Prohlédni `last_error` na uvízlých řádcích. Dispatcher opakuje automaticky každý 5s poll.

### Chybí ČNB fixing pro dnešek

1. Proběhl cron 14:40 Europe/Prague? `kubectl logs -l app=openbank-fx-service | grep "ČNB fixing"`.
2. Backfill ručně (idempotentní): `POST /api/v1/fx/cnb/ingest?date=YYYY-MM-DD` s `ROLE_OPERATOR`.
3. Ověření: `GET /api/v1/fx/cnb/rates/EUR`.

### Žádný platný SPOT kurz / kurz expiroval

`POST /convert` selže, když pro pár neexistuje platný SPOT kurz (seed kurzy mají platnost 1 den). Zajisti čerstvý interní/ECB SPOT kurz pro pár; ČNB fixing je `INDICATIVE` a pro vypořádání konverze se **nepoužívá**.

## Deploy / release

- Per-service CI buildí jen na změněných cestách; `version.txt` vlastní **release-please** (neměň ručně ve feature/fix PR).
- `openapi.yaml:info.version` je samostatná osa (ADR-0048) klasifikovaná z OpenAPI diffu.
- GitOps: ArgoCD přebírá nový image tag; u konfliktů image tagu ber `--ours` (čerstvě sestavený), nikdy slepě `--theirs`.
