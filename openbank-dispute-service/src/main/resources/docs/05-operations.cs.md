# Provoz

## Build

```
./gradlew :openbank-dispute-service:build          # kompilace + testy
./gradlew detekt ktlintCheck koverVerify build     # lokální gate před PR
```

Služba používá konvenční plugin `openbank.quarkus-service`. Kontejnerové image se staví jako **fast-jar** (nikdy uber-jar) přes `openbank-infra/scripts/build-push-service.sh openbank-dispute-service`, přičemž `quarkusBuild` běží nejprve na hostiteli (pravidla GitOps v CLAUDE.md).

## Runtime konfigurace

| Nastavení | Hodnota | Zdroj |
|---|---|---|
| App HTTP port | `8135` | `application.yaml` |
| Management port | `8085`, root-path `/q` | `application.yaml` |
| DB URL (reactive / JDBC) | `postgresql://…/openbank_dispute` | `application.yaml` |
| Kafka topic | `openbank.disputes.dispute.event` | `mp.messaging.outgoing.dispute-events-out` |
| OIDC | realm `openbank`, klient `openbank-services` | `application.yaml` |
| Redis | `redis://localhost:6379` | `application.yaml` |
| OTLP endpoint | `http://localhost:4317` | `application.yaml` |
| Outbox poll | každých `5s`, počáteční zpoždění `5s` | `openbank.outbox` |
| Dispute SLA | `45` dní; chargeback okno `120` dní | `openbank.dispute.*` |
| Rate limit | zapnuto, max `200` souběžných | `openbank.rate-limit` |
| OPA / authz | `OPA_URL`, `authz.enforce=false` (poradní) | `application.yaml` |

Tajemství (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) mají výchozí placeholder `CHANGE_ME_LOCAL_DEV_ONLY` a v ne-dev prostředích musí být injektována z platformového secret store. V `%dev` a `%test` je OIDC vypnuto.

## Serverless tier (ADR-0057)

Provoz reklamací je řízen operátory a nárazový, nikoli konstantní. Je kandidátem pro tier **scale-to-zero / scheduled-warm** dle ADR-0057; `@Scheduled` outbox dispatcher (každých 5s) znamená, že plně nečinné scale-to-zero musí počítat s odtokem outboxu — udržuj alespoň jednu teplou repliku, dokud existují nedoručené outbox řádky. Skutečné přiřazení tieru ověř v GitOps manifestech (TBD, pokud zatím nedeklarováno).

## Health probes

SmallRye Health na management portu (`/q/health`):

- **Liveness** `/q/health/live`
- **Readiness** `/q/health/ready` — zahrnuje konektivitu datasource + Kafka
- **Metriky** `/q/metrics` (Prometheus / Micrometer)
- **Docs** `/q/openbank/docs` (tato dokumentace, ADR-0019)

## SLO (navrhováno)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Dostupnost (read API) | 99,9 % |
| `POST /disputes` p99 latence | < 300 ms |
| Zpoždění publikace outboxu | < 30 s (poll 5s + retry) |
| RTO / RPO | 15 min / 5 min (v souladu s DORA) |

## Runbooky

### Outbox se neodvodňuje
1. Zkontroluj `/q/metrics` na stav scheduleru a circuit-breakeru.
2. Dotaz `SELECT status, count(*) FROM dispute_outbox GROUP BY status;` — řádky zaseknuté v ne-sent s `last_error` indikují problém s publikací do Kafky.
3. Ověř konektivitu Kafky / topic `openbank.disputes.dispute.event`. Circuit breaker se otevírá při 50% selhání přes objem 10; po 5s se pootevře.

### Nesoulad Flyway checksumu při startu
Nikdy needituj aplikovanou migraci. Nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, restartuj, pak odeber po ustálení DB (CLAUDE.md). Pozor: `validate-on-migrate` je zde `false`, takže většina driftu je při migraci tolerována.

### Insert selže s `relation "dispute_outbox_seq" does not exist`
Migrace sekvence V3 se neaplikovala. Spusť migrace znovu / ověř, že V3 je přítomna.

### 401/403 na API
Ověř, že bearer token pochází z realmu `openbank` a nese `ROLE_VIEWER` (čtení) nebo `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_API` (zápisy). Pokud bylo zapnuto vynucení OPA (`AUTHZ_ENFORCE=true`), zkontroluj rozhodovací logy OPA sidecaru.

## Release

Per-service SemVer přes release-please (commit message je changelog). Needituj ručně `version.txt`, `CHANGELOG.md` ani `openapi.yaml:info.version`. Použij `/bump openbank-dispute-service` pro synchronizaci verzí a `/ship-check` před mergem (ADR-0029).
