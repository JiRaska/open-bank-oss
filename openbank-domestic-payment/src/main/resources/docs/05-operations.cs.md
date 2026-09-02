# Provoz

## Build & běh

```bash
# Build (fast-jar, nikdy uber-jar)
./gradlew :openbank-domestic-payment:quarkusBuild

# Lokální brána před PR
./gradlew :openbank-domestic-payment:detekt :openbank-domestic-payment:ktlintCheck \
          :openbank-domestic-payment:koverVerify :openbank-domestic-payment:build

# Docker (multi-stage; build stage JDK 20, runtime JRE 25 alpine, ZGC)
docker build -f openbank-domestic-payment/Dockerfile -t openbank-domestic-payment .
# nebo generický helper:
openbank-infra/scripts/build-push-service.sh openbank-domestic-payment
```

> Vždy fast-jar (`-Dquarkus.package.jar.type=fast-jar`) a build na hostu — Dockerfile i build-push helper to už dělají. Runtime stage běží jako non-root uživatel `openbank`.

## Endpointy

| Cesta | Port | Účel |
|---|---|---|
| `/api/v1/domestic-payments/...` | 8116 | business REST API |
| `/api/v1/info` | 8116 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8116 | Swagger UI |
| `/q/openapi` | 8116 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (tato dokumentace) — management port |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management rozhraní je zapnuté na **odděleném portu 8085** (`quarkus.management.enabled=true`, root-path `/q`).

## Serverless tier (ADR-0057)

`domestic-payment` je **money-path** služba. Dle ADR-0057 jsou money-path hot cesty **Tier T0 (always-on)** — `minReplicas ≥ 1`, nikdy se neškálují na nulu: synchronní platební hop nesmí spolknout cold-start a PSD2 očekává dostupnost plateb. Tier je odvozen z měřeného chování a CI ho kontroluje proti deklarovanému; nepřiřazuje se zde ručně.

## Konfigurace

| Env var | Default | Účel |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | heslo DB — v produkci **nutno** přepsat (Vault) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokeři |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | REST klient sanctions-service (ADR-0032) |
| `AML_SERVICE_URL` | `http://localhost:8117` | REST klient aml-service |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata v `/api/v1/info` |

Resilience parametry (`openbank.resilience.*`): circuit breaker (volume 15, ratio 0.5, success 5, delay 10s), retry (3, 500ms, jitter 200ms), timeout 15s. Interval pollu outboxu 5s. Rate limit 100 souběžných requestů.

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — restart podu při selhání.
- **Readiness:** `/q/health/ready` (port 8085) — DB + Kafka producer + Redis.
- Flyway `connect-retries: 10` (interval 2s) toleruje pomalou DB při startu.

## SLO (cíle)

_Toto jsou cílové návrhové SLO pro produkčně tvarované nasazení — v jednouzlovém sandboxu nejsou měřené, garantované ani plněné._


| Metrika | Cíl | Měření |
|---|---|---|
| Dostupnost | 99,9 % (T0 always-on) | `up{service="openbank-domestic-payment"}` |
| Latence p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latence p95 POST (založení vč. sync screeningu) | < 500 ms | zahrnuje round-trip na sanctions-service |
| Outbox lag | < 10 s | stáří PENDING (poll 5s, dávka 25) |
| Chybovost | < 0,1 % 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooky

### Platby uvíznuté v RECEIVED

Očekávané, když screening vrátí REVIEW (potenciální zásah ≤ 0,85) nebo když byla sanctions-service nedostupná (fail-closed). Postup:
1. Vypsat držené platby: `GET /api/v1/domestic-payments?status=RECEIVED`.
2. Prohlédnout AML případ v `aml-service` (otevřen s alertem `AML_HOLD` / `SCREENING_UNAVAILABLE`).
3. Vyřešit přes `PATCH /{id}/status` → `VALIDATED` (vyčištěno) nebo `REJECTED` s odpovídajícím důvodem. **Neuvolňovat** automaticky.

### Výpadek sanctions-service

Symptom: každé založení vrací `RECEIVED`, AML případy s `SCREENING_UNAVAILABLE`. Toto je fail-closed záměrně. Obnovit sanctions-service, poté přescreenovat držené platby přes ruční review. Zkontrolovat stav circuit-breakeru REST klienta.

### Rostoucí outbox lag

1. `SELECT count(*) FROM domestic_payment_outbox WHERE status='PENDING';`
2. Zkontrolovat dostupnost Kafky a logy dispatcheru (`DomesticPaymentOutboxDispatcher`).
3. Prohlédnout `FAILED` řádky: `SELECT event_id, last_error, attempt_count FROM domestic_payment_outbox WHERE status='FAILED';` — publish je obalený circuit breakerem, takže výpadek Kafky ho rozpojí a samo se zotaví.

### Aktivace a obnova delegovaného utrácení

Receiver delegovaného utrácení je záměrně ve výchozím stavu vypnutý. Aktivujte jej až poté, co
consumer `openbank.delegation.spend-reservation-state` obnoví projekci od `earliest`, lag je nulový
a projekce po terminální revizi následované opožděnou rezervovanou revizí zůstane terminální. U
kompaktovaného proudu se aplikuje nejvyšší `reservationVersion` z payloadu, nikdy poslední pozorovaný
záznam.

Při přechodu na request fingerprint nejprve zastavte vytváření plateb, nechte doběhnout maximální
timeout requestu, přepněte všechny writery na zdravý nový image a teprve pak tvorbu znovu otevřete.
Starý nullable fingerprint záměrně vrací `409 IDEMPOTENCY_KEY_REUSED`; není autoritou pro replay.
U nejednoznačného requestu nevyzývejte klienta k novému klíči — nejprve ověřte stav platby a proveďte
reconciliation.

Finalizer zapínejte jako poslední. Smí uvolnit jen binding, který domestic-payment atomicky označil
za neexistující; timeouty a neznámé výsledky zůstávají rezervované. Při zapnutí musí existovat jeho
workflow-liveness signál. Pro rollback zastavte nové delegované vytvoření, vyčistěte/reconcileujte
všechny rezervované bindingy a outbox záznamy a až pak vypněte writer — nikdy neobnovujte Redis ani
neprokázaný legacy fingerprint jako autoritu requestu.

### Nelegální přechod stavu (409)

Volající zkusil přechod, který stavový automat zakazuje (viz [03 — API](./03-api.md)). Není retryovatelné; oprav cílový stav volajícího.

## Deploy / release

- Per-service path-scoped CI (buildují se jen změněné služby).
- Release přes release-please z Conventional Commits; needitovat ručně `version.txt` (aktuálně `0.3.0`) ani `CHANGELOG.md`.
- **Money-path:** vyžaduje 2 schválení + threat model (`docs/threat-models/openbank-domestic-payment.md`); nikdy se neauto-merguje.
- CD: ArgoCD vyzvedne bump image tagu v GitOps manifestech.
