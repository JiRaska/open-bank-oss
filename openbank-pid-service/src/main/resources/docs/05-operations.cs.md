# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar — nikdy uber-jar)
./gradlew :openbank-pid-service:quarkusBuild

# Dev režim (live reload)
./gradlew :openbank-pid-service:quarkusDev

# Kontejner (fast-jar, běží pod non-root uživatelem `openbank`)
openbank-infra/scripts/build-push-service.sh pid-service
```

Image skládá `.github/workflows/Dockerfile.deploy` z fast-jaru sestaveného na hostu: kopíruje layout `quarkus-app/` do runtime base `eclipse-temurin:25-jre` (glibc, #3354) a startuje s `-XX:+UseZGC`. `openbank-pid-service/Dockerfile` nic nestaví (#3016) — pipeline z něj čte jedinou věc, řádek `EXPOSE`.

## Endpointy / porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/parties/...` | 8105 | byznys REST API |
| `/api/v1/info` | 8105 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Quarkus **management rozhraní je zapnuté na portu 8085** (`quarkus.management.enabled=true`, root-path `/q`). Health, metriky, OpenAPI a docs resource běží tam; byznys API zůstává na 8105. Dockerfile `EXPOSE`-uje 8105 — zajisti, že 8085 je v k8s manifestu rovněž vystaven/scrapován.

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `QUARKUS_DATASOURCE_REACTIVE_URL` | `postgresql://localhost:5432/openbank_pid` | reaktivní DB URL |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://localhost:5432/openbank_pid` | JDBC URL (Flyway) |
| `QUARKUS_DATASOURCE_USERNAME` / `_PASSWORD` | `openbank` / `CHANGE_ME_LOCAL_DEV_ONLY` | DB creds — **v prod přepsat přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokeri |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | Keycloak issuer |
| `QUARKUS_OIDC_CLIENT_ID` / `_CREDENTIALS_SECRET` | `openbank-services` / `CHANGE_ME...` | OIDC klient |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry trasy |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | `@Authorize` enforce vs advisory |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata pro `/api/v1/info` |
| `QUARKUS_LOG_LEVEL` | `INFO` | úroveň logu |

Bezpečnostní hlavičky (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) jsou nastaveny globálně v `application.yaml`.

## Health checky

- **Liveness:** `/q/health/live` — JVM + ArC. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — reaktivní DB pool + Kafka producent (SmallRye Health).

Příklad probe (port 8085 management):

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8085 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8085 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Workload tier (ADR-0057)

pid-service **není money-path** a čtení/zápisy identity jsou nárazové, ne konstantní. Je kandidátem na **scale-to-zero / scale-from-zero tier** dle FinOps workload klasifikátoru (ADR-0057). Pozor: outbox dispatcher `@Scheduled(every = "5s")` potřebuje alespoň jednu běžící repliku pro odbavení `pid_outbox`, takže scale-to-zero politika musí držet warm repliku (nebo přesunout odbavení na KEDA cron/Kafka-lag trigger). Klasifikace tieru je TBD — ověř proti nasazenému manifestu.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="pid-service"}` |
| Latence p95 GET `/parties/{id}` | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST `/parties` | < 300 ms | zahrnuje DB zápis + publish události |
| Outbox lag | < 10 s | stáří nejstaršího řádku `pid_outbox` s `status != SENT` (interval pollu je 5 s) |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Rostoucí outbox lag
1. Spočti uvízlé řádky: `SELECT count(*) FROM pid_outbox WHERE status <> 'SENT';`
2. Prozkoumej selhání: `SELECT event_id, attempt_count, last_error FROM pid_outbox WHERE status='FAILED' ORDER BY updated_at DESC LIMIT 20;`
3. Zkontroluj circuit breaker — opakované `@Timeout`/Kafka chyby ho otevřou na ~5 s. Ověř dostupnost brokera.
4. Zkontroluj, že dispatcher běží na ≥ 1 replice (`@Scheduled` se spouští jen na živém podu).

### Duplicitní identita / 409 při vytvoření
Symptom: `409 CONFLICT` „Party with bankID sub … already exists" nebo porušení unikátního externího id. Příčina: bankID `sub` (nebo jiné `(id_type,id_value)`) je už namapováno. Akce: dohledej existující party přes `GET /by-external-id?type=BANKID_SUB&value=…` a použij ho znovu — nezakládej druhý party (invariant jeden člověk = jeden party).

### Nelegální přechod PID case (400)
Symptom: `400 VALIDATION_ERROR` z `/parties/{id}/case`. Příčina: požadovaný `status` není legální následný stav z aktuálního stavu case. Akce: přečti aktuální `caseLifecycle.status` a zvol povolený přechod (graf definují pravidla `CaseTransitionEngine`).

### Problémy s DB
Vyčerpaný reaktivní PG pool → selhává readiness. Zkontroluj `pg_stat_activity` podle `application_name` služby; ukonči dlouho běžící dotazy; zvedni velikost poolu přes config map.

## Matice verzí tech stacku

Verze jsou pinovány centrálně v `libs.versions.toml` (Quarkus BOM) a vystaveny v `/api/v1/info`.

| Komponenta | Verze |
|---|---|
| Kotlin | dle root toolchainu (Kotlin 2.x) |
| Quarkus | dle `libs.quarkus.bom` (3.x LTS) |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL driver | Quarkus reaktivní PG + JDBC |
| Kafka klient | SmallRye Reactive Messaging |

(Přesné pinované verze se čtou z `gradle/libs.versions.toml` při buildu — viz `/api/v1/info`.)

## Deploy / release

- **Verzování:** `version.txt` (aktuálně `0.3.0`) vlastní release-please; conventional commits řídí bump. Release osa je nezávislá na `openapi.yaml:info.version` (ADR-0048).
- **CI:** path-scoped per-service pipeline (build jen při změnách pod `openbank-pid-service/src/main/**`); `detekt`, `ktlintCheck`, `koverVerify`, testy, SBOM, image build.
- **CD:** ArgoCD vyzvedne nový image tag z GitOps manifestu.
