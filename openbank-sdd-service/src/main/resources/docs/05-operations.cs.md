# Provoz

## Build & běh

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)

# Unit + integrační testy
./gradlew :openbank-sdd-service:test

# Fast-jar build (nikdy uber-jar — viz GitOps pravidla v CLAUDE.md)
./gradlew :openbank-sdd-service:quarkusBuild

# Dev mód (live reload; OIDC + scheduler vyladěny dle %dev profilu)
./gradlew :openbank-sdd-service:quarkusDev
```

Runtime je reaktivní (`io.smallrye.mutiny.Uni`), nikoli Kotlin `suspend`.

## Endpointy a porty

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/sdd/...` | 8129 | byznys REST API |
| `/api/docs` | 8129 | Swagger UI |
| `/api/v1/info` | 8129 | ServiceInfoResource (build metadata) — z `openbank-libs` |
| `/q/openbank/docs` | 8086 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8129 | OpenAPI spec |
| `/q/health` | 8086 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8086 | Prometheus (Micrometer) |

**Management rozhraní** je zapnuto na samostatném portu: `management.port: 8086`, `root-path: /q`. Aplikační HTTP port je **8129**.

## Konfigurace

| Nastavení | Default | Účel |
|---|---|---|
| `quarkus.http.port` | `8129` | aplikační port |
| `quarkus.management.port` | `8086` | management (health/metrics/docs) port |
| datasource reaktivní URL | `postgresql://localhost:5432/openbank_sdd` | PostgreSQL |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **MUSÍ být přepsáno v prod (Vault, ADR-0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | OIDC client secret — **MUSÍ být přepsán v prod** |
| `quarkus.oidc.auth-server-url` | `http://localhost:8080/realms/openbank` | Keycloak issuer |
| Kafka topic (`sdd-events-out`) | `openbank.sdd.event` | cíl publish outboxu |
| `openbank.outbox.poll-interval` | `5s` | tik dispatcheru (rovněž natvrdo `@Scheduled(every = "5s")`) |
| `openbank.sdd.expiry-cron` | `0 15 3 * * ?` | rozvrh sweepu idle-expiry |
| `openbank.sdd.expiry.enabled` | `false` | **sweep idle-expiry je defaultně VYPNUTÝ** |

Placeholdery jsou jen pro dev; produkce musí vstříknout reálné secrety přes Vault (ADR-0017). Logy jsou v defaultním profilu JSON.

## Workload tier (ADR-0057)

`openbank-sdd-service` **není** money-path služba a **není** regulátorem nařízená always-on služba, takže nevyžaduje T0 (always-on) floor. Je to synchronní request/response HTTP služba s dispatcherem transakčního outboxu:

- Dispatcher outboxu je **jediný zapisovatel** — `concurrentExecution = SKIP` plus Deployment pinnutý na **`replicas: 1`** (ADR-0050 N4). Jakékoli rozhodnutí o scale-to-zero / autoscalingu musí zachovat garanci jediného zapisovatele (`FOR UPDATE SKIP LOCKED` claim je evidované zlepšení před povolením multi-writeru).
- Dle ADR-0057 je **default pro novou službu nejnižší tier, který její trigger dovolí**; přesný tier odvozuje FinOps klasifikátor z naměřeného provozu, neurčuje se ručně zde. (TBD — potvrdit přiřazený tier ve výstupu gitops/klasifikátoru.)

## Health probes

- **Liveness:** `/q/health/live` — běží JVM + ArC.
- **Readiness:** `/q/health/ready` — konektivita datasource + Kafka producer.

`scheduler.enabled` je pod `%test` false, takže integrační test řídí dispatch outboxu explicitně a nezávodí s asserty (ADR-0050).

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | Prometheus `up{service="openbank-sdd-service"}` |
| Latence p95 (authorise / lifecycle) | < 150 ms | `http_server_requests_seconds{quantile=0.95}` |
| Zpoždění outboxu | < 10 s | stáří nejstaršího řádku `PENDING`/`FAILED` |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Roste zpoždění outboxu

1. Spočítej backlog: `SELECT status, count(*) FROM sdd_outbox GROUP BY status;`
2. Hledej `DEAD` řádky (poison): `SELECT event_id, event_type, attempt_count, last_error FROM sdd_outbox WHERE status='DEAD';` — ty jsou zaparkované na `MAX_ATTEMPTS` (10) a nikdy se znovu nedispatchují. Prozkoumej `last_error`; ručně znovu zařaď až po opravě příčiny (`status='PENDING'`, `attempt_count=0`).
3. Zkontroluj dosažitelnost Kafky a logy dispatcheru `sdd-service` (`grep SddOutboxDispatcher`); mohl se rozepnout circuit breaker.
4. Ověř, že běží právě jedna replika (`replicas: 1`) — více zapisovatelů by dvojitě publikovalo.

### Volající hlásí nelegální přechod (409)

Symptom: `409 Conflict`, tělo `Illegal mandate transition`. Příčina: volající se pokusil o operaci, kterou stavový automat zakazuje (např. confirm už-ACTIVE mandátu, suspend už SUSPENDED, amend terminálního/pending mandátu). Akce: `GET /mandates/{id}` pro načtení aktuálního `status`; klient by se podle něj měl větvit.

### Sweep idle-expiry

Cron `MandateExpiryScheduler` je **defaultně vypnutý** (`openbank.sdd.expiry.enabled=false`). Pro zapnutí nastav flag a cron (`openbank.sdd.expiry-cron`); sweep označí mandáty `ACTIVE`/`SUSPENDED` nečinné ≥36 měsíců jako `EXPIRED`. Čistá datová aritmetika (`MandateLifecycle.isIdle`) je jednotkově testovaná nezávisle na cronu.

## Deploy / release

- Per-service CI staví fast-jar a kontejnerový image (host-side `quarkusBuild`, ne in-Docker Gradle — viz GitOps pravidla v CLAUDE.md).
- **Release** je automatický přes release-please z Conventional Commits (scope `sdd`). Needituj ručně `version.txt` / `CHANGELOG.md`. `version.txt` je aktuálně `0.2.0`; `quarkus.application.version` v `application.yaml` je `0.1.1` (známý drift — sjednotí je skill `bump` při příští změně).
- **Flyway** běží při startu (`migrate-at-start: true`); `validate-on-migrate` je vypnuté a opakování připojení je nakonfigurováno kvůli pořadí cold-startu vůči DB.
