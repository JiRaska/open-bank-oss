# Provoz

## Build

```
./gradlew :openbank-tpp-registry-service:build      # kompilace + testy služby
./gradlew detekt ktlintCheck koverVerify build      # lokální brána před PR
```

Služba používá konvenční plugin `openbank.quarkus-service` a `openbank-libs`. CI je path-scoped — buildí se jen změněné služby (ADR-0029).

### Build image

Vždy **fast-jar**, build na hostu (nikdy Gradle v Dockeru):

```
openbank-infra/scripts/build-push-service.sh openbank-tpp-registry-service
```

`build-push-service.sh` nejprve lokálně spustí `quarkusBuild`, runtime stage pak COPYne `quarkus-app/`. Nikdy nepoužívej uber-jar — nechá `quarkus-app/` prázdné a pod crashloopuje.

## Konfigurace (klíčové env proměnné)

| Proměnná | Účel | Default |
|---|---|---|
| `POSTGRES_PASSWORD` | DB heslo | `CHANGE_ME_LOCAL_DEV_ONLY` (v prod blokováno) |
| `OIDC_CLIENT_SECRET` | client secret Keycloaku | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | OPA sidecar PDP | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` |
| `AUTHZ_ENFORCE` | přepnutí OPA z advisory na enforce | `false` |
| `BUILD_TIME` / `GIT_COMMIT` | BuildInfo pro `/api/v1/info` | `unknown` |

- **Port aplikace:** 8108. **Management port:** 8085 (root `/q`: health, docs, metriky).
- **Datasource:** `openbank_tpp_registry` na PostgreSQL; reaktivní + JDBC (Flyway) URL.
- **Kafka:** bootstrap z `quarkus.smallrye-reactive-messaging.kafka.bootstrap-servers`; odchozí kanál `tpp-events-out` → topic `openbank.tpp.registry.event`.
- **Redis:** `redis://localhost:6379` (idempotence).
- **Rate limiting:** `openbank.rate-limit.enabled=true`, `max-concurrent-requests=50`.
- **Bezpečnostní hlavičky** (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) nastaveny přes `quarkus.http.header.*`.

## Serverless tier (ADR-0057)

Podle tierů scale-to-zero a FinOps klasifikátoru (ADR-0057) je tpp-registry **nízkoprovozní control-plane registr** čtený hlavně `psd2-service` na AIS/PIS hot path. Kontrola autorizace je citlivá na latenci, takže realistický tier je **warm/min-replicas ≥ 1** (na read path, na kterém PSD2 závisí, neškálovat na nulu). Přiřazení tieru ověřte ve výstupu FinOps klasifikátoru pro tuto službu — přesný label tieru je **TBD**, dokud tam nebude klasifikováno.

## Health a probes

- **Liveness/Readiness:** SmallRye Health na `/q/health` (root-path `/q/health`), obsluhováno na management portu 8085. Readiness pokrývá reaktivní datasource a Kafka klienta.
- **Startup:** Flyway `migrate-at-start` s `connect-retries: 10` / interval `2S` — toleruje DB ještě neready při studeném startu.

## Observabilita

- **Tracing/metriky:** OpenTelemetry OTLP exportér → `http://localhost:4317` (konfigurovatelné); `service.name = openbank-tpp-registry-service`.
- **Logy:** JSON konzolové logování (strukturované) v ne-dev profilech.

## SLO (návrh)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Indikátor | Cíl |
|---|---|
| dostupnost `GET /check` | 99,9 % (závislost PSD2 hot path) |
| latence p99 `GET /check` | ≤ 50 ms (v clusteru) |
| dostupnost register/blacklist | 99,5 % |
| RTO / RPO | RTO 15 min / RPO 5 min (bezstavová app; stav v PostgreSQL) |

## Runbooky

### Kontrola autorizace selhává pro známě dobrý TPP
1. `GET /api/v1/tpp-registry/{tppId}` — ověř `status=ACTIVE` a přítomnost role.
2. Zkontroluj `qwac_expires_at` — expirovaný QWAC dá `403` s důvodem „QWAC certificate expired".
3. Pokud je status `BLACKLISTED`, prohlédni `blacklist_reason` / `blacklisted_at`.

### Nouzový blacklist (kompromitovaný / delicencovaný TPP)
1. `POST /api/v1/tpp-registry/{tppId}/blacklist` s `{ "reason": "<ref incidentu>" }` a `Idempotency-Key`.
2. Ověř, že `GET /check` nyní vrací `403`. PSD2 plochy TPP při dalším volání odmítnou.

### Outbox se nedraní
1. Dotaž `tpp_outbox WHERE status='PENDING' ORDER BY created_at` na backlog.
2. Prohlédni `last_error` / `attempt_count` u `FAILED` řádků; ověř konektivitu Kafky (`tpp-events-out`).
3. Dispatcher běží každých 5 s (`@Scheduled`, batch 25); zaseklý scheduler obnoví restart podu.

### Flyway checksum mismatch při startu
- Nikdy nepřepisuj aplikovanou migraci. Pokud checksum mismatch shodí start, nastav v gitops env `QUARKUS_FLYWAY_REPAIR_AT_START=true`, nech usadit, pak odeber.

## Release

Releasovaná komponenta (má `version.txt`, aktuálně `0.3.0`). Verzování/changelog vlastní release-please z Conventional Commits — neupravuj ručně `version.txt` ani `CHANGELOG.md`. `openapi.yaml:info.version` je samostatná osa API kontraktu (ADR-0048).
