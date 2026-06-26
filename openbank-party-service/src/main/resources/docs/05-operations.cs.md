# Provoz

## Build & spuštění

```bash
# Build (fast-jar — nikdy uber-jar)
./gradlew :openbank-party-service:quarkusBuild

# Dev režim (live reload; OIDC vypnuto v %dev)
./gradlew :openbank-party-service:quarkusDev

# Lokální gate před PR
./gradlew :openbank-party-service:detekt :openbank-party-service:ktlintCheck \
          :openbank-party-service:koverVerify :openbank-party-service:build
```

Integrační testy (`PartyApiIT`) běží proti per-JVM PostgreSQL + Redpanda (Kafka API) přes Testcontainers (CI infra pilot) místo sdíleného compose stacku — viz `build.gradle.kts`. Dědí `DOCKER_HOST` runneru s fallbackem na unix socket.

## Porty & endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/parties/...` | 8111 | business REST API |
| `/api/v1/info` | 8111 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8111 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) |
| `/q/openapi` | 8085 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je zapnuté na portu **8085** (`quarkus.management`, root-path `/q`). V `%test` jsou management rozhraní i OIDC vypnuté.

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — **MUSÍ být přepsáno v prod (Vault)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — přepsat v prod |
| (datasource URL) | `postgresql://localhost:5432/openbank_parties` | reactive + JDBC |
| (kafka) | `localhost:29092` | bootstrap servers |
| (oidc) | `http://localhost:8080/realms/openbank` | issuer |
| `OPENBANK_FLAGS_URL` | `http://localhost:8016` | flagd OFREP endpoint (fail-static) |
| `OPENBANK_FLAGS_TIMEOUT_MS` | `100` | timeout vyhodnocení flagu |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce (ADR-0034) |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Logy jsou JSON v non-dev profilech; OpenTelemetry OTLP export na `http://localhost:4317`. Bezpečnostní response hlavičky (CSP, HSTS, X-Frame-Options atd.) jsou nastaveny globálně.

## Serverless tier (ADR-0057)

Tiering workloadů / scale-to-zero je řízen [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md). party-service je stavová, event-konzumující identity služba (musí být nahoře pro konzumaci KYC/AML eventů a pro dotazy během zakládání účtu), takže patří do always-on tier, nikoli scale-to-zero. Přesný štítek tier ověř proti FinOps klasifikátoru (TBD — není připnuto v service konfiguraci tohoto repa).

## Health checks

- **Liveness** `/q/health/live` — JVM + ArC.
- **Readiness** `/q/health/ready` — DB spojení + Kafka producer/consumer.

Flyway `connect-retries: 10` / interval `2S` pokrývá DB-not-ready-at-boot.

## SLO (cíle)

| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % | `up{service="openbank-party-service"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST create | < 300 ms | DB zápis + outbox insert |
| Outbox lag | < ~10 s (dispatcher pollne každých 5 s) | stáří pending řádku |
| Lag KYC/AML eventů | nízké jednotky sekund | lag consumer group |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Outbox se nevyprazdňuje
1. `SELECT count(*) FROM party_outbox WHERE status='PENDING'`.
2. Zkontroluj dostupnost Kafky pro `openbank.party.events`.
3. Zkontroluj logy dispatcheru: `kubectl logs -l app=party-service | grep PartyOutboxDispatcher`. Circuit breaker může být otevřený — hledej opakované `markFailed` + `last_error`.
4. Vyřeš downstream/broker problém; dispatcher automaticky retryuje na dalším 5s ticku.

### Party zaseknutá v PENDING_KYC
Party přejde na ACTIVE jen když platí **oba** KYC=APPROVED a AML=CLEARED. Zkontroluj `kyc_status` a `aml_status` na řádku. Pokud jeden koncový event nikdy nedorazil, ověř, že ho kyc-/aml-service emitovala (jsou zdrojem pravdy a mohou přehrát — consumer je poison-pill safe a vadné eventy ackne).

### Party zaseknutá PENDING i po obou signálech
Ověř, že konzumované eventy použily přesně rozpoznávané typy (`KYC_CASE_APPROVED`/`KYC_CASE_REJECTED`; AML `newStatus/status` = `CLEARED`/`BLOCKED`). Nerozpoznané hodnoty jsou záměrně ignorovány.

### Duplicitní party 409 při create
E-mail je unikátní. 409 znamená, že e-mail už existuje — dohledej ho přes `GET /parties/search` nebo podle e-mailu; neretryuj naslepo.

### Flyway checksum mismatch při startu
Způsobeno přepsanou aplikovanou migrací. Nastav `QUARKUS_FLYWAY_REPAIR_AT_START=true` v gitops env, nech usadit, pak odstraň. Nikdy needituj aplikovanou migraci — oprav dopředu (srov. V5→V6).

## Deploy / release

- Per-service path-scoped CI (buildne jen při změnách v `openbank-party-service/**`).
- fast-jar Docker image (nikdy uber-jar) přes `openbank-infra/scripts/build-push-service.sh party`.
- Verzování je per-service SemVer; `version.txt` vlastní release-please (neměň ručně). `openapi.yaml:info.version` je nezávislá osa API kontraktu (ADR-0048).
