# Operations

## Build & run

```bash
# Build (locally, fast-jar — never uber-jar)
./gradlew :openbank-fx-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-fx-service:quarkusDev

# Generic image build/push (host-side build, not in-Docker Gradle)
openbank-infra/scripts/build-push-service.sh fx-service
```

The local pre-PR gate: `./gradlew detekt ktlintCheck koverVerify build`. Kover line floor is **40%** (money-path baseline per `rules.yaml`; aspirational target 70%).

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/fx/...` | 8119 | business REST API |
| `/api/v1/fx/cnb/...` | 8119 | ČNB fixing ingest/read |
| `/api/v1/info` | 8119 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8119 | Swagger UI |
| `/q/openapi` | 8119 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation, management port) |
| `/q/health` | 8085 | liveness + readiness |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is enabled on **port 8085**, root-path `/q` (`quarkus.management`). SmallRye Health, Micrometer/Prometheus and OpenTelemetry (OTLP → `:4317`) are wired in.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **must be overridden in prod via Vault (ADR 0017)** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `CNB_FEED_URL` | `https://www.cnb.cz/.../denni_kurz.txt` | ČNB fixing feed |
| `CNB_CURRENCIES` | `EUR,USD,GBP` | currencies ingested from the ČNB fixing |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sanctions screening |
| `AML_SERVICE_URL` | `http://localhost:8117` | AML case store |

Datasource: `postgresql://localhost:5432/openbank_fx`. Kafka bootstrap: `localhost:29092`. OIDC issuer: `http://localhost:8080/realms/openbank`, client `openbank-services`. OIDC is **disabled** in `%dev` and `%test`. Rate limit: `openbank.rate-limit` (max 200 concurrent requests). Outbox poll: 5s. Security response headers (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, etc.) are set in `application.yaml`.

## Serverless tier (ADR-0057)

`fx-service` is a **money-path** service (`rules.yaml: money_path_services`). The `POST /convert` call is a **synchronous money hop** with a synchronous downstream screening call, so cold-start latency is unacceptable → **Tier T0 — Always-on** (`minReplicas ≥ 1`, never scales to zero), with a PodDisruptionBudget for voluntary-disruption cover. Demoting it below T0 requires the ADR-0030 threat model + 2 approvals (T0 is "sacred" for money-path).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC. Pod restart on failure.
- **Readiness:** `/q/health/ready` (port 8085) — DB pool + Kafka producer + downstream client config.

Flyway has `connect-retries: 10` / `connect-retries-interval: 2S` so the pod tolerates a DB not-yet-ready at boot.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | `up{service="openbank-fx-service"}` |
| Latency p95 GET rate | < 100 ms | `http_server_requests_seconds` |
| Latency p95 POST convert | < 500 ms | includes synchronous sanctions screen |
| Outbox lag | < 30 s | PENDING-row age (5s poll, batch 25) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Conversions stuck in PENDING

PENDING means screening said REVIEW or the sanctions service was unavailable.
1. Check sanctions-service health: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. Check fx-service logs for `SCREENING_UNAVAILABLE` / `AML_HOLD`.
3. An AML case should exist in `aml-service` for each PENDING conversion — resolution is via the AML case lifecycle, not by re-POSTing convert (idempotency key would just return the PENDING record).

### Outbox lag growing

1. `SELECT count(*) FROM fx_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the dispatcher's circuit breaker state in logs (`FxOutboxDispatcher`).
3. Inspect `last_error` on stuck rows. The dispatcher retries automatically on each 5s poll.

### ČNB fixing missing for today

1. Did the 14:40 Europe/Prague cron run? `kubectl logs -l app=openbank-fx-service | grep "ČNB fixing"`.
2. Backfill manually (idempotent): `POST /api/v1/fx/cnb/ingest?date=YYYY-MM-DD` with `ROLE_OPERATOR`.
3. Verify: `GET /api/v1/fx/cnb/rates/EUR`.

### No valid SPOT rate / rate expired

`POST /convert` fails when no valid SPOT rate exists for the pair (the seed rates have a 1-day validity). Ensure a fresh internal/ECB SPOT rate exists for the pair; the ČNB fixing is `INDICATIVE` and is **not** used for conversion settlement.

## Deploy / release

- Per-service CI builds only on changed paths; `version.txt` is owned by **release-please** (do not hand-bump in a feature/fix PR).
- `openapi.yaml:info.version` is a separate axis (ADR-0048) classified from the OpenAPI diff.
- GitOps: ArgoCD picks up the new image tag; for image-tag merge conflicts take `--ours` (freshly-built), never blind `--theirs`.
