# Provoz

## Build a spuštění

```bash
# Build (lokálně) — fast-jar (nikdy uber-jar)
./gradlew :openbank-psd2-service:quarkusBuild -Dquarkus.package.jar.type=fast-jar

# Dev režim (live reload; OIDC vypnuté v %dev)
./gradlew :openbank-psd2-service:quarkusDev

# Lokální brána před PR
./gradlew detekt ktlintCheck koverVerify build
```

Dockerfile je multi-stage build na `eclipse-temurin:25` (JDK build stage, JRE runtime stage), kopíruje fast-jar layout `quarkus-app/`, běží jako non-root uživatel `openbank` a startuje s `-XX:+UseZGC`. `EXPOSE`uje `8107`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/open-banking/v2/...` | 8107 | Open Banking AIS / PIS / souhlasy (směrem k TPP) |
| `/open-banking/sandbox/v2/...` | 8107 | sandbox fixtures (bez autentizace) |
| `/open-banking/docs` | 8107 | Swagger UI |
| `/api/v1/info` | 8107 | service / build metadata |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/health` | 8085 | liveness + readiness (`smallrye-health`, root-path `/q`) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je zapnuté na samostatném portu (`quarkus.management.port=8085`, `host=0.0.0.0`, `root-path=/q`). OpenTelemetry OTLP trasy exportují na `:4317`.

## Konfigurace

| Nastavení / env | Výchozí | Účel |
|---|---|---|
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | OIDC client secret služby — **přepsat v prod** |
| `TPP_REGISTRY_SERVICE_URL` | `http://localhost:8108` | base URL REST klienta tpp-registry |
| `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers` | `localhost:29092` | Kafka brokeři |
| `quarkus.redis.hosts` | `redis://localhost:6379` | idempotenční cache |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | Keycloak realm |
| `openbank.psd2.sandbox-mode` | `true` | sandbox povrch zapnut |
| `openbank.psd2.idempotency-ttl-seconds` | `86400` | TTL idempotenční cache |
| `openbank.rate-limit.max-concurrent-requests` | `100` | strop souběhu |
| `openbank.outbox.poll-interval` / `initial-delay` | `5s` / `5s` | kadence dispatcheru outboxu |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build provenance vystavená na `/api/v1/info` |

Parametry odolnosti žijí pod `openbank.resilience.{circuit-breaker,retry,timeout}` a mapování topicu pod `mp.messaging.outgoing.psd2-events-out` (topic `openbank.psd2.events`, String serializery).

Bezpečnostní response hlavičky jsou nastaveny globálně (`X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS, `Referrer-Policy`, `Permissions-Policy`). CORS je omezen na `http://localhost:3000` s allowlistem Open Banking hlaviček (`Consent-ID`, `Idempotency-Key`, `TPP-*`, `X-TPP-ID`, `SSL-CLIENT-S-DN`, …).

## Health checky

- **Liveness:** `/q/health/live` — JVM + ArC běží.
- **Readiness:** `/q/health/ready` — Kafka producer + konektivita Redis (kromě outboxu žádná byznysová DB k hlídání).

## Serverless tier (ADR-0057)

PSD2 je **externě vystavený, TPP řízený** povrch s nárazovým, na latenci citlivým provozem a fail-closed závislostí na souhlasu. V rámci scale-to-zero workload tierů (viz [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md)) by měl být klasifikován tak, aby během pracovní doby udržoval teplou minimální repliku a vyhnul se cold-start latenci u volání TPP; přesné přiřazení tieru řídí FinOps klasifikátor (TBD — ověřte proti živému výstupu klasifikátoru, ne natvrdo zde).

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="openbank-psd2-service"}` |
| Latence p95 AIS GET | < 150 ms | `http_server_requests_seconds{quantile=0.95}` (vč. ověření souhlasu + downstream čtení) |
| Latence p95 PIS POST | < 400 ms | zahrnuje ověření souhlasu + iniciaci v transaction-service |
| Zpoždění outboxu | < 10 s | stáří nejstaršího `PENDING` řádku (poll interval 5 s) |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### TPP dostává 503 SERVICE_UNAVAILABLE

Příčina: circuit breaker `tpp-registry` otevřen nebo registr nedostupný.
1. Zkontrolujte zdraví a dostupnost tpp-registry z podu (`TPP_REGISTRY_SERVICE_URL`).
2. Prozkoumejte logy na `TPP registry circuit open` / `authorization failed`.
3. Breaker se po nakonfigurovaném delay (5 s) automaticky zotaví, jakmile je registr zdravý; manuální reset není potřeba.

### TPP nečekaně dostává 401 CONSENT_INVALID

Příčina: `consent-service` vrátil neplatné, **nebo** se spustil fallback ověření souhlasu (selhává uzavřeně, vrací `false`).
1. Zkontrolujte zdraví consent-service — downstream výpadek degraduje na odepření.
2. Ověřte, že scope souhlasu odpovídá operaci (`ACCOUNTS_READ` / `BALANCES_READ` / `TRANSACTIONS_READ` / `*_INITIATE`).
3. Potvrďte, že souhlas neexpiroval (při vytvoření omezeno na 90 dní).

### Roste zpoždění outboxu

1. Spočítejte čekající řádky: `SELECT count(*) FROM psd2_outbox WHERE status='PENDING'`.
2. Zkontrolujte dostupnost Kafky a topic `openbank.psd2.events`.
3. Prozkoumejte logy `Psd2OutboxDispatcher`; publikační cesta je omezena bulkheadem (1) a circuit breakerem — výpadek Kafky parkuje řádky jako `PENDING`/`FAILED` a ty se opakují při dalším pollu.

### Idempotenční replay

Opakované PIS volání se stejným `Idempotency-Key` (stejný `tppId`+produkt) vrátí cachovaný `201` s `X-Idempotency-Replayed: true`. To je očekávané a bezpečné.

## Flyway operace

Migrace jsou po aplikaci neměnné. Pokud checksum mismatch blokuje start na živé DB, dočasně nastavte `QUARKUS_FLYWAY_REPAIR_AT_START=true` a po ustálení jej odstraňte (nikdy nepřepisujte aplikovanou migraci).

## Deploy / release

- Per-service path-scoped CI buildy jen změněné služby. Release je automatický přes **release-please** z Conventional Commits — nikdy ručně needitujte `version.txt` ani `CHANGELOG.md`. Aktuální release verze: `version.txt = 0.3.0`.
- Build/push image přes `openbank-infra/scripts/build-push-service.sh openbank-psd2-service` (host-side `quarkusBuild`, fast-jar). GitOps image tagy: při merge konfliktech berte `--ours` pro image řádky.
