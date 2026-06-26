# Provoz

## Build a spuštění

```bash
# Build (lokálně) — fast-jar (nikdy uber-jar)
./gradlew :openbank-sepa-payment:quarkusBuild

# Dev mód (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-sepa-payment:quarkusDev

# Lokální brána před PR
./gradlew :openbank-sepa-payment:detekt ktlintCheck koverVerify build

# Obecný build image (host-side gradle, pak Docker COPY quarkus-app/)
openbank-infra/scripts/build-push-service.sh sepa-payment
```

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/sepa-payments/...` | 8115 | business REST API |
| `/api/v1/info` | 8115 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8115 | Swagger UI (`quarkus.swagger-ui.path`) |
| `/q/openapi` | 8085 | OpenAPI spec (management) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> Management rozhraní je povoleno na samostatném portu `8085` s root-path `/q` (`quarkus.management.*`).

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **v prod MUSÍ být přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **v prod přes Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokeři |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | REST klient sanctions-service (screening gate) |
| `AML_SERVICE_URL` | `http://localhost:8117` | REST klient aml-service (otevírání případů) |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA přepínač advisory→enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata pro `/api/v1/info` |

DB je dostupná přes reaktivní (`postgresql://…/openbank_sepa_payments`) i JDBC (Flyway) URL. Redis na `redis://localhost:6379` zajišťuje idempotenci.

## Health checky

- **Liveness:** `/q/health/live` — běží JVM + ArC. Při selhání restart podu.
- **Readiness:** `/q/health/ready` — DB pool + Kafka producer + Redis.

Poznámka ke **screening gate**: REST klienti sanctions/AML jsou obaleni MicroProfile Fault Tolerance (circuit breaker, retry, timeout — viz `openbank.resilience.*`). Výpadek sanctions **neshodí** readiness; způsobí, že `createPayment` je fail-closed (platba držena v `RECEIVED`).

## FinOps tier (ADR-0057)

**T0 — Always-on.** Jako synchronní money-path skok nesmí sepa-payment polknout cold-start, takže `minReplicas ≥ 1`; nikdy neškáluje na nulu. Tier je odvozen z měřeného chování a gateován declared-vs-measured v CI.

## SLO

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="openbank-sepa-payment"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (create vč. sync screen) | < 800 ms | zahrnuje zápis DB + 2× volání sanctions screen |
| Outbox lag | < 10 s | stáří nejstaršího PENDING řádku `sepa_payment_outbox` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Platby uvíznuté v RECEIVED

To je **záměr**, když screening vrátí REVIEW nebo je nedostupný (fail-closed, ADR-0032).
1. Zkontroluj zdraví sanctions služby: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. Vypiš držené platby: `SELECT payment_id, status, created_at FROM sepa_payments WHERE status='RECEIVED' ORDER BY created_at`.
3. Zkontroluj odpovídající AML případy v aml-service (alert kódy `AML_HOLD` / `SCREENING_UNAVAILABLE`).
4. Držená platba je uvolněna nebo zamítnuta přes životní cyklus AML případu / přechod `PATCH …/status`, nikdy automaticky.

### Rostoucí outbox lag

1. `SELECT count(*) FROM sepa_payment_outbox WHERE status='PENDING'`.
2. Zkontroluj dostupnost Kafky a logy dispatcheru (`SepaPaymentOutboxDispatcher`).
3. Prozkoumej `last_error` / `attempt_count` na FAILED řádcích; po opakovaných selháních publikace může být circuit breaker otevřený.

### Idempotenční replay

Opakovaný `POST` se stejným `Idempotency-Key` vrátí cachovanou odpověď s `X-Idempotency-Replayed: true`. Prázdný klíč vrátí `400`. Neřeš to opakovaným použitím klíče s jiným tělem — generuj nový klíč per logická platba.

### DB / migrace

Nikdy neměň aplikovanou migraci (checksum mismatch → pád při startu). U settlované živé DB použij `QUARKUS_FLYWAY_REPAIR_AT_START=true`, pak odeber.

## Matice verzí tech stacku

Rozřešeno z `libs.versions.toml` při buildu, vystaveno v `/api/v1/info`:

| Komponenta | Verze |
|---|---|
| Kotlin | 2.x |
| Quarkus | 3.x LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate | Reactive (Panache) |

> Přesné připnuté verze vlastní `openbank-libs` `libs.versions.toml`; tato služba je dědí přes konvenční plugin `openbank.quarkus-service`.

## Deploy / release

Per-service CI pipeline + release-please (per-service komponenta, `version.txt`). Jako **money-path** služba vyžaduje merge **2 schválení + aktuální threat model**. Image se buildí jako fast-jar, host-side gradle pak Docker COPY `quarkus-app/`. CD přes ArgoCD vyzvedne nový image tag z GitOps manifestu.
