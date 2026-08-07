# Provoz

## Build & běh

```bash
# Build (lokálně) — host-side quarkusBuild, fast-jar
./gradlew :openbank-kyc-service:quarkusBuild

# Dev mód (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-kyc-service:quarkusDev

# Kontejnerový image (generický builder)
openbank-infra/scripts/build-push-service.sh kyc-service
```

**fast-jar** (`-Dquarkus.package.jar.type=fast-jar`) se staví na hostu; image skládá `.github/workflows/Dockerfile.deploy`, které kopíruje `quarkus-app/`. Runtime image: `eclipse-temurin:25-jre` (glibc, #3354), non-root uživatel `openbank`, ZGC. Nikdy nepoužívej uber-jar (prázdný `quarkus-app/` → crashloop). `openbank-kyc-service/Dockerfile` nic nestaví (#3016) — pipeline z něj čte jen `EXPOSE`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/kyc/...` | 8114 | business REST API |
| `/api/v1/info` | 8114 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8114 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management endpointy jsou na vyhrazeném management portu **8085** (`quarkus.management.enabled=true`, root-path `/q`); business API je na **8114**.

## Konfigurace

| Env proměnná | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **MUSÍ být v prod přepsáno přes Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — Vault v prod |
| `BUILD_TIME`, `GIT_COMMIT` | `unknown` | build metadata vystavené na `/api/v1/info` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar (ADR-0034) |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA decision path |
| `AUTHZ_ENFORCE` | `false` | advisory vs enforce autorizace |
| `openbank.kyc.auto-approve` | `false` | **pouze sandbox** straight-through schválení; v prod MUSÍ zůstat false |

Datasource: `postgresql://…/openbank_kyc`. Kafka bootstrap: `localhost:29092` (přepisováno per prostředí). Standardní bezpečnostní response hlavičky jsou nastaveny v `application.yaml` (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, atd.).

## Health checky

- **Liveness:** `/q/health/live` — JVM + ArC běží. Restart podu při selhání.
- **Readiness:** `/q/health/ready` — DB spojení (reaktivní PG) + zapojení Kafka producer/consumer.

Flyway je nakonfigurován s `connect-retries: 10` (interval 2 s), takže start toleruje DB, která ještě nastartovává.

## Serverless tier (ADR-0057)

`kyc-service` je request-driven compliance back-office služba (žádná kontinuální hot path); je kandidátem na **scale-to-zero / scale-down** tier dle ADR-0057. Jedna výhrada: provozuje Kafka **konzumenta** (`party-events-in`) a 5s outbox `@Scheduled` dispatcher, takže plně scale-to-zero nasazení by pozastavilo auto-open a vyprazdňování událostí — zvol tier podle toho (drž ≥1 repliku, pokud je vyžadována kontinuální konzumace). Přiřazený tier ověř proti FinOps klasifikátoru.

## SLO (orientační)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl |
|---|---|
| Dostupnost | 99,9 % (back-office, vážené pracovní dobou) |
| Latence čtení (p99) | < 200 ms |
| Latence mutací (p99) | < 500 ms |
| Zpoždění vyprázdnění outboxu | < 15 s (dispatcher každých 5 s, batch 25) |
| RTO / RPO | 15 min / 5 min (v souladu s DORA) |

## Runbooky

- **Roste backlog outboxu** — zkontroluj řádky `kyc_outbox` se `status != SENT` a rostoucím `attempt_count` / `last_error`; ověř dostupnost Kafky a stav `@CircuitBreaker` v dispatcheru.
- **Případy se neotevírají automaticky** — ověř, že konzument `party-events-in` je připojen ke skupině `kyc-service-party` a že `PARTY_CREATED` teče na `openbank.party.events`. `openCaseForParty` je idempotentní; replay topicu je bezpečný.
- **Chyba duplicitního případu při insertu** — očekávané při replayi/scale-outu; `uq_kyc_cases_active_party` odmítne losera a kód znovu načte. Žádná akce, pokud chyby nepřetrvávají pro různé party.
- **Flyway checksum mismatch při startu** — nikdy nepřepisuj aplikovanou migraci; nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v GitOps env, po ustálení odstraň.
- **Sandbox auto-approve v prod** — pokud se případy schvalují s revizorem `sandbox-auto-approval` v ne-sandbox prostředí, `openbank.kyc.auto-approve` je špatně nastaven; okamžitě přepni na `false` (compliance incident, obejití čtyř očí).

## Observabilita

OpenTelemetry OTLP traces na `:4317` (`service.name=openbank-kyc-service`), Micrometer/Prometheus metriky na `/q/metrics`, JSON console logging. Build metadata (`gitCommit`, `buildTime`, verze) na `/api/v1/info` (identifikace dle DORA čl. 9).
