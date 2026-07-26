# Operations

## Build & run

```bash
# Build (fast-jar)
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:quarkusBuild

# Dev mode (live reload)
./gradlew :openbank-anacredit-service:quarkusDev

# Tests (domain tests are pure JUnit; AnaCreditResourceTest is @QuarkusTest)
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-anacredit-service:test --offline
```

Image builds use **fast-jar** (`-Dquarkus.package.jar.type=fast-jar`); the runtime stage COPYs `quarkus-app/`. Generic build: `openbank-infra/scripts/build-push-service.sh anacredit-service`.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/anacredit/exposures` | 8137 | register / list exposures |
| `/api/v1/anacredit/returns/{referenceDate}` | 8137 | render the AnaCredit return |
| `/api/v1/info` | 8137 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8137 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8137 | OpenAPI spec |
| `/api/docs` | 8137 | Swagger UI |
| `/q/health` | 8137 | liveness + readiness (SmallRye Health) |

App and management share port **8137** (no separate management port configured).

## Configuration

From `application.yaml` (ADR-0037 v2 adds a PostgreSQL datasource + Flyway; no Kafka / Redis):

| Setting | Value | Purpose |
|---|---|---|
| `quarkus.http.port` | `8137` | app + management port |
| `quarkus.http.cors.origins` | `http://localhost:3000,http://openbank-admin-ui:3000` | admin UI origin allow-list |
| security headers | `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS, etc. | hardened response headers |
| `quarkus.datasource.reactive.url` / `.jdbc.url` | `openbank_anacredit` (localhost default) | reactive Panache app traffic / Flyway migrations |
| `quarkus.flyway.migrate-at-start` | `true` | schema applied on boot |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI document |

Auth (Keycloak OIDC issuer, realm) and the datasource credentials are supplied by the deployment environment, not hard-coded in `application.yaml`.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — service ready to serve. As of ADR-0037 v2, readiness now depends on the reactive Postgres connection pool being reachable (SmallRye Health's built-in datasource check).

## FinOps workload tier (ADR-0057)

| Axis | Assessment |
|---|---|
| Money-path / mandated-continuous? | **No** — not in `rules.yaml: money_path_services`; derive-only |
| Trigger | synchronous HTTP request/response (no resident listener, no Kafka consumer) |
| Traffic shape | rare / bursty — exposures fed and returns rendered around monthly reference dates |
| Cold-start tolerance | high — regulatory rendering is not latency-critical |

⇒ **Tier T1 — HTTP → 0** (scale from/to zero on inbound HTTP via the KEDA HTTP add-on). Idle compute cost ≈ 0; the `openbank_anacredit` Postgres instance itself is now a small always-on cost line (previously zero under the in-memory v1 store) — exposures registered before a scale-to-zero event now **survive** the next cold start (this is the whole point of ADR-0037 v2). Cold start additionally needs a live reactive-pool connection before readiness passes. The tier is *derived from measured traffic*, not hand-assigned (ADR-0057), so this is the recommended classification, subject to the declared-vs-measured CI gate.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Note |
|---|---|---|
| Availability | best-effort (T1, not T0) | scale-to-zero tolerated; no continuous-service mandate |
| Latency p95 (render return) | < 200 ms warm | reactive Panache query over `credit_exposures` (indexed on `debtor_id`) |
| Cold-start | within HTTP add-on budget | Quarkus fast-jar starts in tens to hundreds of ms; add Postgres pool handshake |
| Error rate | < 0.1% 5xx | unexpected errors carry a correlation id via libs |

## Runbooks

### Return looks empty / under-reports after a deploy

1. Exposures are now durable (ADR-0037 v2) — a pod restart alone should **not** lose them. If the return is empty, first confirm the Flyway migration actually applied: `SELECT * FROM flyway_schema_history;` should show `V2__create_credit_exposures` as `success`.
2. If the migration is missing or the table is genuinely empty, re-run the exposure feed (re-POST exposures) before rendering the return.
3. Verify with `GET /api/v1/anacredit/exposures` that the expected instruments are present.

### Instrument unexpectedly excluded

1. Render the return and read its `exclusions` trail — every drop has a `reason`.
2. `HOUSEHOLD_OUT_OF_SCOPE` ⇒ `debtorType` is `NATURAL_PERSON` (correct: AnaCredit is legal-entity only).
3. `BELOW_THRESHOLD` ⇒ the debtor's **aggregated** `committedAmountEur` across all instruments is `< €25 000`; check the EUR amount supplied (FX sourcing is the caller's job).
4. `NO_EXPOSURE` ⇒ both committed and drawn are zero.

### 403 on every call

The resource requires one of `ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_API`. Check the token's realm roles.

## Tech-stack version matrix

| Component | Version |
|---|---|
| Kotlin | 2.x (platform `libs.versions.toml`) |
| Quarkus | 3.x (enforced BOM) |
| JDK runtime | 25 (Eclipse Temurin) |
| Jackson | via Quarkus BOM (`jackson-module-kotlin`, `jackson-datatype-jsr310`) |

Exact pinned versions are auto-generated from `libs.versions.toml` at build and surfaced in the `/api/v1/info` payload.

## Deploy / release

- Per-service path-scoped CI builds only when files under `openbank-anacredit-service/src/main/**` change.
- `version.txt` is owned by **release-please** (per-service component); feature/fix PRs do not hand-edit it.
- The OpenAPI contract version (`openapi.yaml:info.version`) is the separate API-contract axis (ADR-0048) and is verified by `AnaCreditContractTest`.
- CD: ArgoCD picks up the new image tag from the gitops manifest bump.
