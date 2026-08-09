# Provoz

## Build

```
./gradlew :openbank-analytics-sink:build          # pouze unit, bez infry (buildovatelné offline)
./gradlew :openbank-analytics-sink:build -PwithDocker   # spustí i Docker-backed adaptérové ITy
./gradlew detekt ktlintCheck koverVerify build     # lokální gate před PR
```

- **Tech:** Kotlin / Quarkus 3 LTS / JDK 25 (`jvmToolchain(25)`). Závislosti: SmallRye Reactive Messaging (Kafka), SmallRye Health, Micrometer/Prometheus, OpenTelemetry, OIDC, Scheduler, Fault Tolerance, kotlinx-coroutines.
- Výchozí task `test` je **pouze unit a bez infry** (zachovává příslib offline-buildovatelnosti); adaptérové ITy s `@Tag("integration")` (ClickHouse / Vault / Apicurio / S3) se samy přeskočí bez Dockeru a běží jen s `-PwithDocker`.
- **SBOM:** CycloneDX (`cyclonedxBom`, schéma 1.5, runtime classpath).

> **Poznámka k Dockerfile:** aktuální `Dockerfile` builduje s `-Dquarkus.package.type=uber-jar`, přičemž runtime stage COPYuje fast-jar layout `quarkus-app/`. Podle zpevněného build-pravidla repa musí service image používat **fast-jar** (`-Dquarkus.package.jar.type=fast-jar`); uber-jar flag nechá `quarkus-app/` prázdné a pod crashloopuje. **Toto je známý nesoulad k opravě** (sladit build flag Dockerfile s COPYovaným layoutem, nebo použít `openbank-infra/scripts/build-push-service.sh analytics-sink`). Označeno jako follow-up.

## Konfigurace (env)

Všechny externí závislosti jsou **opt-in** přes env, výchozí jsou offline no-op/logging bindingy:

| Oblast | Klíčové env proměnné | Výchozí |
|---|---|---|
| Cíl sinku | `ANALYTICS_SINK_TYPE` (`clickhouse`), `CLICKHOUSE_URL/DB/USER/PASSWORD` | LoggingAnalyticsSink (offline) |
| Rekonciliace | `ANALYTICS_RECONCILE_CRON` (`0 30 2 * * ?`), `ANALYTICS_RECONCILE_SOURCE_BACKEND` (`http`), `..._ENDPOINTS` | jen sklad, bez zdroje |
| Backfill | `ANALYTICS_BACKFILL_CHUNK` (`PT24H`) | — |
| Schema governance | `ANALYTICS_SCHEMA_BACKEND` (`apicurio`), `ANALYTICS_SCHEMA_KNOWN`, `ANALYTICS_SCHEMA_STRICT` | config katalog, brána otevřená |
| Výmaz | `ANALYTICS_ERASURE_BACKEND` (`vault`), `ANALYTICS_VAULT_*` | NoOpCryptoErasure |
| WORM | `ANALYTICS_WORM_BACKEND` (`s3`), `ANALYTICS_WORM_S3_*` | zrcadlo/logging |
| Health / RPO | `ANALYTICS_MAX_LAG_SECONDS` (`900`), `ANALYTICS_MAX_DEAD_LETTERS` (`100`) | — |
| Rezidence | `ANALYTICS_RESIDENCY_REGION` (`eu-north-1`), `..._ALLOWED`, `..._ENFORCE` (`true`) | vynuceno |
| Auth | `OIDC_CLIENT_SECRET` | dev placeholder (blokován v prod) |

## Deploy & FinOps tier

- **Mimo request-path** (asynchronní Kafka konzument + nízkoprovozní operátorský REST), takže služba je **kandidátem na scale-to-zero** podle ADR-0057. FinOps klasifikátor čte naměřené signály (Kafka consumer lag + active fraction, HTTP idle ratio, využití CPU/replik) a doporučí tier; páka zpět na `min>0` je naměřený zásah do p95 SLO. Tier je **unclassified/declared v CI**, ne ručně tvrzený (TBD do výstupu klasifikátoru).
- **Guardrail:** při scale-to-zero musí být Kafka consumer lag dohnán v rámci RPO; readiness probe (níže) chrání proti tichému lagu.

## Health probes

- **Readiness:** `IngestHealthCheck` (`@Readiness`, název `analytics-ingest-freshness`) hlásí **DOWN**, když ingest lag (`now - occurredAt`) překročí `max-lag-seconds` (výchozí 900 s) nebo počet dead-letterů překročí `max-dead-letters` (výchozí 100). Před první událostí je **UP** (čerstvý sink není „stale").
- **Liveness:** standardní SmallRye Health.
- **Startup:** `DataResidencyValidator` přeruší boot, pokud `residency.region` není na allow-listu (při `enforce=true`).
- **Endpointy:** management port 8086, root-path `/q` (`/q/health`, `/q/metrics`, `/q/openbank/docs`).

## Observabilita

- **Metriky:** Micrometer → Prometheus (`/q/metrics`).
- **Tracing:** OpenTelemetry OTLP → `http://...:4317`, `service.name=openbank-analytics-sink`.
- **Logy:** JSON console logging (`quarkus.log.console.json=true`) mimo dev.

## SLO (návrh)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Ingest freshness (lag) | p95 ≤ 900 s (RPO; readiness blokuje nad tím) |
| Míra dead-letterů | < 100 nevyřízených (readiness blokuje nad tím) |
| Rekonciliační drift | 0 nevysvětlených per-agregát verzních neshod při denním běhu |
| Dostupnost (operátorský REST) | best-effort — mimo money-path |

## Runbooky

- **Lag / readiness DOWN:** zkontroluj lag Kafka consumer-group `analytics-sink` a health ClickHouse sinku; při scale-to-zero ověř, že workload nastartoval a dohání. `ANALYTICS_MAX_LAG_SECONDS` lač pouze po dohodě s SRE.
- **Rostoucí dead-lettery:** prozkoumej `dead_letter_events.error`; obvyklou příčinou je producent emitující neznámé/novější schéma. Po opravě producenta operátor přehraje `raw_payload` normální mapovací cestou. Při `ANALYTICS_SCHEMA_STRICT=true` srovnej i katalog schémat (config nebo Apicurio).
- **Rekonciliační neshoda:** přečti `GET /api/v1/analytics/reconciliation/last`; spusť **backfill na čtyři oči** (`POST /backfill/proposals` → schválení jiným operátorem → execute) k zaplnění mezery; řádek `backfill_audit` je evidence.
- **GDPR žádost o výmaz:** `POST /api/v1/analytics/erasure`; očekávej buď crypto-shred, nebo doložené odmítnutí pod zákonným hold.
- **Integrity challenge:** přepočítej leaf hashe z `bronze_events` a ověř, že reprodukují každý `integrity_anchors.merkle_root` (autoritativní kopie ve WORM/S3 Object Lock).

## Follow-upy (TBD)

- Zatím žádný verzovaný `openapi.yaml` + contract test (viz [03 — API](./03-api.md)).
- Žádný `version.txt` — služba **zatím není release component** (není v `release-please-config.json`); `build.gradle.kts` fixuje `0.1.0-SNAPSHOT`.
- Nesoulad fast-jar vs uber-jar v Dockerfile (výše).
