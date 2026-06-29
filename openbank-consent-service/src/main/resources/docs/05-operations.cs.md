# Provoz

## Build & běh

```bash
# Build (lokálně, fast-jar)
./gradlew :openbank-consent-service:quarkusBuild

# Dev mód (live reload, OIDC vypnuto)
./gradlew :openbank-consent-service:quarkusDev

# Docker (multi-stage, fast-jar; viz Dockerfile)
docker build -t openbank/consent-service -f openbank-consent-service/Dockerfile .
```

Runtime entrypoint používá ZGC: `java -XX:+UseZGC -jar /app/quarkus-run.jar` na Eclipse Temurin 25 JRE (alpine), jako non-root uživatel.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/consents/...` | 8106 | business REST API |
| `/api/v1/info` | 8106 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8106 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (management port) |

Management rozhraní je na **samostatném portu 8085** (`quarkus.management.port`, root-path `/q`); business API je na 8106.

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ být přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | OIDC client secret — **v prod přepsat** |
| `SCA_SERVICE_URL` | `http://localhost:8110` | základní URL sca-service (REST klient) |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | cesta OPA rozhodnutí |
| `AUTHZ_ENFORCE` | `false` | přepni na `true` pro vynucení OPA rozhodnutí (ADR 0034) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build provenance v `/api/v1/info` |

DB (reaktivní): `postgresql://localhost:5432/openbank_consents`; Kafka `localhost:29092`; Redis `redis://localhost:6379`. Rate limiting je zapnut (`openbank.rate-limit.max-concurrent-requests=150`).

## Serverless tier (ADR 0057)

consent-service je na **kritické cestě řízené požadavky** (validate se volá inline před poskytnutím Open-Banking dat), proto **není** kandidátem na scale-to-zero — latence studeného startu při volání validate by porušila latenční SLO a zablokovala provoz TPP. Běží jako vždy zapnutý deployment s teplým minimálním počtem replik. (Pokud je v `openbank-infra` publikována tabulka tieringu, consent-service je tam zařazena odpovídajícím způsobem.)

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC běží.
- **Readiness:** `/q/health/ready` (port 8085) — DB pool + Kafka producer + Redis dostupné.

SmallRye Health je namountováno na `/q/health`. Probes by měly cílit na management port 8085.

## SLO

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="openbank-consent-service"}` |
| Latence p95 `validate` | < 50 ms | `http_server_requests_seconds{uri=~".*/validate",quantile=0.95}` |
| Latence p95 `POST /consents` | < 300 ms | zahrnuje DB zápis |
| Latence p95 `activate` | < 500 ms | zahrnuje synchronní SCA ověřovací round-trip |
| Zpoždění outboxu | < 10 s | dispatcher běží každých 5 s, batch 25 |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Roste zpoždění outboxu

1. Spočítej pending: `SELECT count(*) FROM consent_outbox WHERE status='PENDING';`
2. Prozkoumej selhání: `SELECT event_id, attempt_count, last_error FROM consent_outbox WHERE last_error IS NOT NULL ORDER BY updated_at DESC LIMIT 20;`
3. Zkontroluj dostupnost Kafky a logy dispatcheru (`ConsentOutboxDispatcher`).
4. Circuit-breaker dispatcheru může být otevřený — potvrď zdraví brokeru, poté se sám zotaví v dalším 5s ticku.

### Aktivace vrací 503

Symptom: `POST /{id}/activate` → `503 SERVICE_UNAVAILABLE`. Příčina: sca-service nedostupná po retries / otevřený circuit breaker. Akce: zkontroluj `SCA_SERVICE_URL`, zdraví sca-service a network policy; aktivaci lze bezpečně opakovat, jakmile je SCA zpět (souhlas zůstává `PENDING_SCA`).

### Aktivace vrací 422 SCA not completed/mismatch

Odkazovaná výzva není `COMPLETED`, nebo její `partyId`/`purpose` (`CONSENT_GRANT`) neodpovídá. To je očekávané, když zákazník nedokončil SCA — není to incident. Neexistuje žádný auto-approve fallback (ADR 0021).

### Flyway checksum mismatch při startu

Příčina: byla editována aplikovaná migrace. Dočasná oprava: nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v gitops env, nech usadit, pak odstraň. Nikdy nepřepisuj aplikovanou migraci — přidej novou `V{n}`.

## Matice verzí tech stacku

| Komponenta | Verze |
|---|---|
| Kotlin | 2.3.20 |
| Quarkus | 3.33.2 LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate Reactive (Panache) | přes Quarkus BOM |

## Deploy / release

- **Verzování:** per-service SemVer ve `version.txt` (aktuálně `0.2.0`), vlastněno **release-please** — feature/fix PR ho ručně needitují (CLAUDE.md #3).
- **Verze API kontraktu:** `openapi.yaml info.version` (`1.0.0`) je samostatná osa (ADR 0048).
- **Money-path:** každá změna vyžaduje 2 schválení + aktuální threat model (`docs/threat-models/openbank-consent-service.md`, ADR 0030) a nikdy se nemerguje automaticky.
- **CD:** image se buildí na hostu (fast-jar) a rolluje přes ArgoCD z gitops manifestů.
