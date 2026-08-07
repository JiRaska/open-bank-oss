# Provoz

## Build a spuštění

```bash
# Build (lokálně) — fast-jar, nikdy uber-jar
./gradlew :openbank-aml-service:quarkusBuild

# Dev režim (live reload, OIDC vypnuté v %dev)
./gradlew :openbank-aml-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build

# Kontejnerový image (jednofázový; recept je .github/workflows/Dockerfile.deploy)
#   build: hostem, ./gradlew build -Dquarkus.package.jar.type=fast-jar
#   runtime: eclipse-temurin:25-jre (glibc, #3354), non-root uživatel, -XX:+UseZGC
```

> Build používá host-side Gradle build, fast-jar packaging (`-Dquarkus.package.jar.type=fast-jar`) dle GitOps pravidel repa — nikdy in-Docker Gradle, nikdy uber-jar.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/aml/cases/...` | 8117 | byznysové REST API |
| `/api/v1/info` | 8117 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8117 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8117 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je na **samostatném portu 8085** (`quarkus.management.enabled=true`, root-path `/q`). V `%test` je vypnuté.

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v produkci MUSÍ být přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | secret Keycloak klienta — v produkci Vault |
| (datasource) | `postgresql://localhost:5432/openbank_aml` | reaktivní + JDBC URL |
| (kafka) | `localhost:29092` | Kafka bootstrap servers |
| (redis) | `redis://localhost:6379` | idempotenční cache |
| (oidc) | `http://localhost:8080/realms/openbank` | OIDC issuer, klient `openbank-services` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar PDP (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | přepne OPA z advisory na enforce |
| `openbank.aml.auto-clear` | `false` | **pouze sandbox** auto-clear onboarding případů — v produkci ponechat `false` |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Logování je JSON do konzole (`quarkus.log.console.json=true`), člověkem čitelné v `%dev`.

## Odolnost

Konfigurováno pod `openbank.resilience` a na Kafka publisheru:
- **Rate limit:** `max-concurrent-requests=100`.
- **Circuit breaker:** requestVolumeThreshold 10, failureRatio 0.5, successThreshold 5, delay 10s.
- **Retry:** maxRetries 2, delay 300ms, jitter 150ms. **Timeout:** 15s (request), 3s (Kafka publish).
- **Publisher:** `@Bulkhead(1,1)`, `@CircuitBreaker`, `@Retry`, `@Timeout(3000)`.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — dostupné datasource + Kafka + Redis.

Flyway běží při startu (`migrate-at-start=true`, 10 connect retries × 2s), takže studená DB nezhodí pod okamžitě.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99.9% | Prometheus `up{service="aml-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (založení případu) | < 300 ms | zápis DB + insert outbox |
| Outbox lag | < 10 s | stáří nejstaršího PENDING řádku `aml_outbox` (dispatcher běží každých 5s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Serverless tier (ADR-0057)

`aml-service` nemá **explicitně deklarovaný tier** v `rules.yaml: finops.tiering.declared` a není money-path služba, takže dědí default řízený klasifikátorem (unclassified, dokud není změřen). Outbox dispatcher je `@Scheduled` a Deployment musí běžet na **`replicas: 1`** jako jediný outbox writer — scale-to-zero je tedy omezeno dispatcherem: jakýkoli tiering musí udržet právě jednoho writera teplého (platí guardraily cold-start / single-writer z ADR-0057).

## Runbooky

### Roste outbox lag

1. Spočítej PENDING: `SELECT count(*) FROM aml_outbox WHERE status='PENDING';`
2. Ověř dostupnost Kafky: `kcat -L -b kafka:9092`.
3. Zkontroluj logy dispatcheru: `kubectl logs -l app=aml-service | grep AmlOutboxDispatcher`.
4. Ověř právě jednu replicu (jediný writer). Pokud řádek uvázl ve FAILED s vyčerpanými retry, přejde do DEAD — prozkoumej `last_error`.

### Rozhodnutí odmítnuto s 409

Symptom: `409 conflict` (`InvalidAmlCaseStateTransitionException`). Příčina: požadovaný `targetStatus` není platný přechod z aktuálního stavu, nebo je případ už terminální (`CLEARED`/`BLOCKED`). Akce: znovu načti případ, zvol platný přechod; terminální případy nelze znovu otevřít.

### Onboarding případy se v sandboxu neclearují

Zkontroluj `openbank.aml.auto-clear` — ve výchozím stavu je `false`. V produkci je to záměr (vyžadováno rozhodnutí analytika). V sandboxu nastav na `true`, aby `PartyEventConsumer` onboarding případy auto-clearoval.

### Flyway checksum mismatch při startu

Nikdy needituj aplikovanou migraci. Pokud checksum mismatch blokuje start, nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, nech DB ustálit, pak odeber.

## Deploy / release

- Per-service CI pipeline (path-scoped): test → fast-jar build → SBOM → build/push image → ArgoCD zvedne tag.
- **Bump verze:** jakákoli změna pod `src/main/**` bumpuje `version.txt` dle commit typu — ale release axis vlastní release-please; neprováděj ruční bump `version.txt` proti otevřenému Release PR.
- U konfliktů image-tagů v GitOps ber `--ours` (čerstvě sestavený tag), nikdy slepě `--theirs`.

## Testy

- Unit: `AmlCaseServiceTest`, `AmlCaseTest` (stavový automat), `AmlOutboxDispatchTest`.
- Integrační: `AmlOutboxDispatchIT` s `PostgresRedisTestResource` (Testcontainers — izolovaný Postgres + Valkey na test JVM, CI infra sweep #578). V `%test` je scheduler vypnutý, takže IT řídí `dispatchScheduledBatch()` explicitně.
- Coverage je ratchet-only (Kover, ADR-0020) — nikdy nesnižovat.
