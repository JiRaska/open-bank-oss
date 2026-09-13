# Provoz

## Build

```
./gradlew :openbank-sepa-instant:build
./gradlew detekt ktlintCheck koverVerify build   # lokální brána před PR
```

Sestaveno přes konvenční plugin `openbank.quarkus-service` (ADR-0049 D1). Kover práh řádkového pokrytí **40 %** (money-path baseline, jen ratchet; cíl 70 %).

## Image a deploy

- **Vždy fast-jar, nikdy uber-jar** — Dockerfile kopíruje `quarkus-app/`; uber-jar ho nechá prázdný → crashloop (gotcha repa).
- Build na hostiteli, ne in-Docker Gradle: `openbank-infra/scripts/build-push-service.sh openbank-sepa-instant`.
- Nasazeno přes GitOps/ArgoCD.

## Serverless tier (ADR-0057)

**T0 — Always-on.** sepa-instant je v `rules.yaml: money_path_services` a cesta submitu je **synchronní peněžní hop, kde latence studeného startu je nepřijatelná** (zúčtování do 10 s). Dle [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md): `minReplicas ≥ 1`, **nikdy neškáluje na nulu**, plná dostupnost. Demotion T0→níže by potřeboval ADR-0030 threat model + 2 schválení — money path je posvátná.

## Porty a endpointy

- **App:** 8127 (HTTP). Bezpečnostní hlavičky nastaveny globálně (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, nosniff, atd.).
- **Management:** 8085, root path `/q` (health, metriky, docs).
- Swagger UI: `/api/docs`.
- Docs-as-Service: `/q/openbank/docs` (ADR-0019).

## Health probes

SmallRye Health (`quarkus-smallrye-health`) na management portu:
- `/q/health/live` — liveness.
- `/q/health/ready` — readiness (zahrnuje reaktivní datasource).

## Pozorovatelnost

- **Metriky:** Micrometer → Prometheus (`/q/metrics`).
- **Trasování:** OpenTelemetry OTLP → `:4317` (resource atribut `service.name = openbank-sepa-instant`).
- **Logy:** JSON konzole (`quarkus.log.console.json = true`) mimo dev; plain text v `%dev`.

## SLO (money-path, indikativní)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Indikátor | Cíl |
|---|---|
| Latence submit→rozhodnutí (vč. prověrky) | < 10 s (execution-timeout = 10 s) |
| Dostupnost | T0 always-on; v souladu s kontinuitou money-path |
| RTO / RPO | 15 min / 5 min (platformová výchozí, viz ADR-0057/DORA) |

## Konfigurační páky

| Vlastnost | Výchozí | Význam |
|---|---|---|
| `openbank.sct-inst.execution-timeout-seconds` | 10 | deadline watchdogu nastavený při přechodu do PROCESSING |
| `openbank.sct-inst.recall-window-days` | 10 | okno pro recall |
| `openbank.rate-limit.max-concurrent-requests` | 500 | strop souběhu |
| `openbank.resilience.circuit-breaker.*` | vol 20 / ratio 0.3 / succ 10 / 5 s | breaker prověrkového hopu |
| `openbank.resilience.retry.*` | 2 / 100 ms / 50 ms jitter | retry |
| `openbank.resilience.timeout.value-ms` | 10000 | timeout volání |
| `AUTHZ_ENFORCE` | false | OPA advisory vs enforce (ADR-0034) |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | REST klient sankcí |
| `AML_SERVICE_URL` | `http://localhost:8117` | REST klient aml |

Tajemství (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) přicházejí z prostředí; výchozí hodnoty `CHANGE_ME_LOCAL_DEV_ONLY` jsou pouze dev placeholdery.

## Runbooky

### Platby uvízlé v PENDING
Nárůst `PENDING` znamená, že sankční brána platby drží — buď skutečné REVIEW výstupy, nebo pravděpodobněji **sanctions-service je nedostupná** (fail-closed, ADR-0032 §C). Zkontroluj health sanctions-service a stav circuit-breakeru. Každá podržená platba má otevřený AML případ (`AML_HOLD` nebo `SCREENING_UNAVAILABLE`); řeš přes aml-service / compliance ops. Platby jsou uloženy, nikdy ztraceny.

### Zpoždění publikace událostí
Události se publikují přímo a synchronně ze `SctInstPaymentService` přes `KafkaSctInstEventPublisher` — na této cestě není žádný outbox ani dispatcher (dřívější outbox pipeline byla odstraněna jako mrtvý kód, issue #1034). Zaostává-li `openbank.sepa.instant.events`, zkontroluj health Kafka emitteru / brokeru a logy na straně producenta u samotného volání publikace; signálem je zaseknutý přechod stavu platby, ne zaseknutá backlog tabulka.

### Flyway checksum mismatch při startu
Způsobeno přepsanou aplikovanou migrací. Dočasná oprava: nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, pak odeber, jakmile se DB ustálí. Nikdy nepřepisuj aplikovanou migraci.

### Timeout provedení
Platby v `PROCESSING` po `execution_timeout_at` jsou vyzvednuty `findTimedOut` (parciální index) a přepnuty na `TIMEOUT` s událostí `SctInstPaymentTimeout`.
