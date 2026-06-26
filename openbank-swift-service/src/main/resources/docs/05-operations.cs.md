# Provoz

## Build & běh

```bash
# Build (fast-jar — nikdy uber-jar)
./gradlew :openbank-swift-service:quarkusBuild

# Dev režim (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-swift-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build
```

Docker image: vícestupňový `Dockerfile` (Eclipse Temurin 25 JDK build → JRE-alpine runtime, non-root uživatel `openbank`, fast-jar layout `quarkus-app/`, `-XX:+UseZGC`, `EXPOSE 8122`). Obecný build helper: `openbank-infra/scripts/build-push-service.sh swift-service` (host-side `quarkusBuild`, ne Gradle v Dockeru).

## Endpointy / porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/swift/...` | 8122 | business REST API |
| `/api/docs` | 8122 | Swagger UI (`always-include: true`) |
| `/api/v1/info` | 8122 | ServiceInfoResource (build metadata) |
| `/q/openapi` | 8085 | OpenAPI dokument |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | SmallRye Health (liveness + readiness) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní běží na samostatném portu (`quarkus.management.port: 8085`, root-path `/q`).

## Konfigurace

| Env proměnná | Výchozí | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB heslo — **MUSÍ být přepsáno v prod (Vault)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **MUSÍ být přepsáno v prod** |
| Kafka bootstrap | `localhost:29092` | `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` |
| Redis hosts | `redis://localhost:6379` | podpora cache/idempotence |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar base URL (ADR-0034) |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA query path |
| `OPA_TIMEOUT_MS` | `500` | timeout OPA rozhodnutí |
| `AUTHZ_ENFORCE` | `false` | OPA **výchozí advisory**; přepni na enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Bezpečnostní hlavičky jsou nastaveny na HTTP vrstvě (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff atd.). CORS povoluje `http://localhost:3000` s `Idempotency-Key` mezi povolenými hlavičkami.

## Resilience (z konfigurace)

- **Rate limit:** zapnuto, `max-concurrent-requests: 200`.
- **Circuit breaker:** request-volume-threshold 10, failure-ratio 0.4, success-threshold 3, delay 15s.
- **Retry:** max 2, delay 1s, jitter 0.5s. **Timeout:** 60s.
- **Outbox dispatch** má vlastní SmallRye Fault Tolerance anotace (viz [02 — Architektura](./02-architecture.md)).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — běh JVM + ArC.
- **Readiness:** `/q/health/ready` (port 8085) — dosažitelnost datasource (a nakonfigurované messaging/redis).

Graceful shutdown: `quarkus.shutdown.timeout: 30s`.

## Serverless tier (ADR-0057)

`openbank-swift-service` je **money-path** HTTP služba ([ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)). Vysokohodnotové wire instrukce jsou citlivé na dostupnost, takže vhodný tier je **T0 — Always-on** (`minReplicas ≥ 1`, nikdy neškáluje na nulu); přesun na nižší tier by vyžadoval ADR-0030 threat model + 2 schválení a musel by být odůvodněn měřeným chováním v nečinnosti. Tier je odvozen z měřeného provozu, ne přiřazen ručně.

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="swift-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (submit) | < 300 ms | zahrnuje validaci + DB zápis |
| Outbox lag | < 5 s (dispatcher pollu je každých 5s) | stáří pending na `swift_outbox` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Outbox lag / události neproudí

1. Počet pending: `SELECT count(*) FROM swift_outbox WHERE status='PENDING'`.
2. Prozkoumej selhání: `SELECT event_id, attempt_count, last_error FROM swift_outbox WHERE status='FAILED'`.
3. Zkontroluj logy dispatcheru pro `SwiftOutboxDispatcher` (otevřený circuit breaker?) a dosažitelnost Kafka brokeru.
4. Dispatcher běží každých 5s s `concurrentExecution = SKIP`; zaseknutý publish vyhodí breaker (otevře po 10 voláních při poměru selhání 0.5, delay 5s).

### Flyway checksum mismatch při startu

Symptom: `FlywayValidateException`. Příčina: aplikovaná migrace byla upravena. Fix: nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true`, restartuj, pak flag odstraň, jakmile se DB usadí. Nikdy nepřepisuj aplikované migrace.

### Chybějící `swift_outbox_seq`

Symptom: outbox INSERT selže s `relation "swift_outbox_seq" does not exist`. Příčina: `V3` neaplikováno. Fix: zajisti spuštění migrace V3; pokryto `HibernateSequenceGuardTest`.

### Konflikt idempotency key

Opakovaný submit se stejným `idempotencyKey` vrátí stávající zprávu. `409` značí duplicitní klíč s konfliktním payloadem — neopakuj; oprav klienta nebo použij nový klíč.

## Testy & pokrytí

- Testy: `SwiftServiceTest`, `SwiftMessageTest`, `HibernateSequenceGuardTest` (Quarkus JUnit5, AssertJ, MockK).
- Pokrytí: Kover line floor **40 %** (money-path baseline, ratchet-only, dle `rules.yaml`); zvyšuj s přibývajícími testy směrem k cíli 70 % pro money-path.

## Deploy / release

- **Release osa:** release-please vlastní `version.txt` (aktuálně `0.2.0`); nikdy needituj ručně. Changelog řídí Conventional Commits.
- **API osa:** `openapi.yaml: info.version` (1.0.0) bumpováno nezávisle z OpenAPI diffu.
- CI je path-scoped (buildí se jen změněné služby). CD přes ArgoCD při bumpu image tagu.
- **Money-path:** každý PR vyžaduje 2 schválení + udržovaný [threat model](../../../../docs/threat-models/openbank-swift-service.md).
