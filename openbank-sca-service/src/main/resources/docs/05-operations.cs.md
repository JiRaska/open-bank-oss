# Provoz

## Build

```
./gradlew :openbank-sca-service:build
./gradlew detekt ktlintCheck koverVerify build   # lokální gate před PR
```

Práh pokrytí (kover): LINE ≥ 40 (money-path baseline; ratchet-only, cíl 70). Kover vyjímá třídy anotované `@Path` / `@ApplicationScoped` / `@RegisterForReflection`.

## Build image a deploy

- **fast-jar, build na hostu** (CLAUDE.md GitOps pravidla). Image skládá `.github/workflows/Dockerfile.deploy`: kopíruje `quarkus-app/` do runtime base `eclipse-temurin:25-jre` (glibc, #3354) a běží jako non-root uživatel `openbank` s `-XX:+UseZGC`. `openbank-sca-service/Dockerfile` nic nestaví (#3016) — pipeline z něj čte jedinou věc, `EXPOSE 8110`.
- Generický build: `openbank-infra/scripts/build-push-service.sh sca-service`.
- **Flyway**: `migrate-at-start: true` s 10 connect-retries. Pokud kdy dojde k checksum mismatch na živé DB, dočasně nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true`, pak odeber po ustálení (nikdy nepřepisuj aplikovanou migraci).

## Porty

| Port | Účel |
|---|---|
| 8110 | aplikační HTTP (`/api/v1/sca/**`) |
| 8085 | management — health, metriky, docs (`root-path /q`) |

## Health probes

- Liveness/readiness přes `quarkus-smallrye-health` na `/q/health` (management port 8085): `/q/health/live`, `/q/health/ready`.
- Health datasource, Redis a Kafka přispívá do readiness přes příslušná Quarkus rozšíření.

## Serverless tier (ADR-0057)

sca-service je **money-path ⇒ T0** (`rules.yaml: t0_baseline = money_path_services`). T0 znamená `min > 0` replik — **neškáluje se na nulu**, protože studený start v cestě autorizace plateb by přidal latenci do synchronního peněžního toku. Demotace pod T0 vyžaduje aktualizaci threat modelu dle ADR-0030 + 2 schválení.

## Pozorovatelnost

- **Metriky:** Micrometer → Prometheus (`/q/metrics`).
- **Trasování:** OpenTelemetry → OTLP endpoint (`OTEL_EXPORTER_OTLP_ENDPOINT`, výchozí `http://localhost:4317`).
- **Logy:** strukturovaný JSON s `traceId` / `spanId` (dev profil přepíná na plain text).

## Konfigurace (env)

| Proměnná | Účel |
|---|---|
| `POSTGRES_PASSWORD` | heslo DB (dev placeholder `CHANGE_ME_LOCAL_DEV_ONLY`) |
| `OIDC_CLIENT_SECRET` | secret Keycloak klienta |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `@Authorize` enforce vs advisory (výchozí `false`) |
| `BUILD_TIME` / `GIT_COMMIT` | BuildInfo pro `/api/v1/info` |

`openbank.sca.idempotency-ttl-seconds` (výchozí 300), `openbank.rate-limit.max-concurrent-requests` (100), `openbank.outbox.poll-interval` (5s) jsou laditelné.

## SLO (cíl)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Dostupnost | 99,9 % (money-path, T0) |
| `POST /challenges` p95 latence | < 300 ms (vyjma out-of-band akce uživatele) |
| Zpoždění dispatche outboxu | < 15 s (5 s poll + rozpočet na retry) |
| RTO / RPO | 15 min / 5 min |

## Runbooky

### Outbox se nevyprazdňuje
Symptomy: události `DEVICE_ENROLLED` nedorazí do `openbank.sca.challenge.event`; řádky `sca_outbox` zaseknuté mimo `SENT`.
1. Zkontroluj konektivitu Kafky a kanál `sca-events-out`; circuit breaker dispatcheru může být otevřený (volume 10, failure ratio 0,5).
2. Prohlédni `last_error` u zaseknutých řádků. Přechodné chyby se opakují (max 2, jitter); trvalá selhání vyžadují opravu downstreamu.
3. Dispatcher polyká chyby scheduleru dle návrhu (scheduler nikdy nespadne) — spoléhej na metriky/logy, ne na restart podu.

### Push/biometrické výzvy se nikdy nedokončí
Očekávané, pokud nebylo posláno žádné rozhodnutí zařízení — **to je korektní fail-closed chování** (ADR-0021), ne chyba. Ověř, že zapsané zařízení skutečně zavolalo `POST /challenges/{id}/decision` s platným podpisem. Nesoulad podpisu vrací `401 InvalidDeviceAssertion`; zkontroluj veřejný klíč zařízení a že podepsaný payload odpovídá `id|decision|amount|currency|creditorIban|reference`.

### Redis nedostupný
Selže OTP store, idempotence a store rozhodnutí. Výzvy nelze spolehlivě vytvářet/ověřovat; ber jako tvrdý výpadek závislosti a postupuj dle platformového Redis runbooku. Žádná trvalá data se neztratí (Postgres drží záznam výzvy).

### Flyway checksum mismatch při startu
Nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v gitops env, nech pod nastartovat, pak odeber. Nikdy needituj aplikovanou migraci.
