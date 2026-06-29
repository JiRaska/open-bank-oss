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

From `application.yaml` — minimal surface (no DB / Kafka / Redis env in v1):

| Setting | Value | Purpose |
|---|---|---|
| `quarkus.http.port` | `8137` | app + management port |
| `quarkus.http.cors.origins` | `http://localhost:3000,http://openbank-admin-ui:3000` | admin UI origin allow-list |
| security headers | `X-Content-Type-Options`, `X-Frame-Options: DENY`, CSP `default-src 'self'`, HSTS, etc. | hardened response headers |
| `quarkus.swagger-ui.path` | `/api/docs` | Swagger UI |
| `quarkus.smallrye-openapi.path` | `/q/openapi` | OpenAPI document |

Auth (Keycloak OIDC issuer, realm) and any future datasource settings are supplied by the deployment environment / `openbank-libs` defaults, not hard-coded in `application.yaml`.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — service ready to serve. v1 has no external datastore dependency, so readiness does not gate on a DB/Kafka pool.

> **Operational note:** the in-memory store is non-durable — a pod restart loses all registered exposures, which must be re-fed before the next return is rendered. This is acceptable for v1's batch-style usage and is removed by the planned PostgreSQL persistence.

## FinOps workload tier (ADR-0057)

| Axis | Assessment |
|---|---|
| Money-path / mandated-continuous? | **No** — not in `rules.yaml: money_path_services`; derive-only |
| Trigger | synchronous HTTP request/response (no resident listener, no Kafka consumer) |
| Traffic shape | rare / bursty — exposures fed and returns rendered around monthly reference dates |
| Cold-start tolerance | high — regulatory rendering is not latency-critical |

⇒ **Tier T1 — HTTP → 0** (scale from/to zero on inbound HTTP via the KEDA HTTP add-on). Idle cost ≈ 0. The non-durable in-memory store means each cold start begins empty; the upstream feed re-registers exposures before a return is requested. The tier is *derived from measured traffic*, not hand-assigned (ADR-0057), so this is the recommended classification, subject to the declared-vs-measured CI gate.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Note |
|---|---|---|
| Availability | best-effort (T1, not T0) | scale-to-zero tolerated; no continuous-service mandate |
| Latency p95 (render return) | < 200 ms warm | pure in-memory aggregation over the exposure set |
| Cold-start | within HTTP add-on budget | Quarkus fast-jar starts in tens to hundreds of ms |
| Error rate | < 0.1% 5xx | unexpected errors carry a correlation id via libs |

## Runbooks

### Return looks empty / under-reports after a deploy

1. v1 store is in-memory and **lost on restart**. Confirm the pod is fresh: `kubectl get pod -l app=anacredit-service -o wide`.
2. Re-run the exposure feed (re-POST exposures) before rendering the return.
3. Verify with `GET /api/v1/anacredit/exposures` that the expected instruments are present.

### Instrument unexpectedly excluded

1. Render the return and read its `exclusions` trail — every drop has a `reason`.
2. `HOUSEHOLD_OUT_OF_SCOPE` ⇒ `debtorType` is `NATURAL_PERSON` (correct: AnaCredit is legal-entity only).
3. `BELOW_THRESHOLD` ⇒ the debtor's **aggregated** `committedAmountEur` across all instruments is `< €25 000`; check the EUR amount supplied (FX sourcing is the caller's job).
4. `NO_EXPOSURE` ⇒ both committed and drawn are zero.

### 403 on every call

The resource requires one of `ROLE_OPERATOR / ROLE_ADMIN / ROLE_AUDITOR / ROLE_COMPLIANCE / ROLE_SERVICE`. Check the token's realm roles.

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
