# Operations

## Build & run

```bash
# Build (locally, fast-jar — never uber-jar)
./gradlew :openbank-customer-edge:quarkusBuild -Dquarkus.package.jar.type=fast-jar

# Run dev mode (live reload)
./gradlew :openbank-customer-edge:quarkusDev

# Container image (from openbank-infra/)
openbank-infra/scripts/build-push-service.sh customer-edge
```

The image is built by `.github/workflows/Dockerfile.deploy` from a host-side fast-jar — runtime base `eclipse-temurin:25-jre` (glibc, #3354), non-root `openbank` user, `-XX:+UseZGC`, port 8128. `openbank-customer-edge/Dockerfile` builds nothing (#3016); the pipeline reads exactly one thing from it, the `EXPOSE` line.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/customer/v1/...` | 8128 | customer-facing REST API (proxy) |
| `/api/v1/info` | 8128 | ServiceInfoResource (build metadata) |
| `/q/openbank/docs` | 8128 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8128 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (management port) |

Note the **separate management port 8085** (`quarkus.management.port`), so probes and metrics are not exposed on the internet-facing 8128.

## Configuration

| Property / env var | Default | Purpose |
|---|---|---|
| `quarkus.http.port` | `8128` | app port |
| `quarkus.management.port` | `8085` | health/metrics port |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://keycloak.iam.svc:8080/realms/openbank-customers` | inbound JWT validation (JWKS fetch) |
| `OIDC_CLIENT_ID` | `openbank-edge` | inbound OIDC client id |
| `QUARKUS_OIDC_TOKEN_ISSUER` | `https://kc.open-bank.tech/realms/openbank-customers` | **pinned public issuer** of customer tokens |
| `openbank.upstream.token-url` | `http://keycloak.iam.svc:8080/realms/openbank` | operator-realm token endpoint (M2M) |
| `openbank.upstream.client-id` | `openbank-edge` | M2M client id |
| `OPENBANK_UPSTREAM_CLIENT_SECRET` | *(none)* | **M2M client secret — supplied at runtime (Vault); must be set or every upstream call 401→502** |
| `openbank.upstream.connect-timeout-ms` | `5000` | upstream connect timeout |
| `openbank.upstream.request-timeout-ms` | `10000` | upstream per-request timeout |
| `ACCOUNT_SERVICE_URL` … `STATEMENT_SERVICE_URL` | in-cluster DNS | upstream base URLs (one per proxied service) |

> **Gotcha (from the source comments):** do **not** declare `client-secret` in `application.yaml` with a `${UPSTREAM_OIDC_CLIENT_SECRET:}` expansion — an ENV-form property name resolves unreliably and silently yields `""`, making every token fetch 401. Supply it via the env var `OPENBANK_UPSTREAM_CLIENT_SECRET` (standard ENV→dotted-property mapping).

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — JVM + ArC running.
- **Readiness:** `/q/health/ready` (port 8085) — SmallRye Health. Being stateless, the edge has no DB/Kafka readiness gate; reachability of Keycloak/upstreams is observed at request time (degrades to 401/502) rather than failing readiness.

## FinOps workload tier (ADR-0057)

The edge is a **stateless HTTP request/response service** — the canonical candidate for **Tier T1 (HTTP → 0)**: scale from/to zero on inbound HTTP (KEDA HTTP add-on / Knative), since Quarkus fast-jar cold-starts in tens of milliseconds. It is **not** a money-path always-on (T0) service (see [06 — Compliance](./06-compliance.md)). Per ADR-0057 the tier is derived from measured traffic, not hand-assigned; T1 is the default for a pure HTTP edge.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="customer-edge"}` |
| Latency p95 GET (proxied read) | < 250 ms | `http_server_requests_seconds{quantile=0.95}` (includes one upstream hop) |
| Latency p95 POST (payment initiate, enriched) | < 600 ms | includes 1–2 upstream resolution hops + the initiate call |
| Error rate | < 0.5% 5xx | `http_server_requests_seconds_count{status=~"5.."}` (502 = upstream transport) |

## Runbooks

### All upstream calls return 502

Symptom: every proxied call returns `{"error":"upstream unavailable"}`.

1. **Check the M2M secret first** — a missing/blank `OPENBANK_UPSTREAM_CLIENT_SECRET` makes `client_credentials` return 401, which the edge surfaces as 502. Verify the env var/secret is mounted.
2. Check the token endpoint reachability: `openbank.upstream.token-url` (operator realm) resolves and responds 200 to a `client_credentials` POST.
3. Check upstream DNS/reachability for the affected `*_SERVICE_URL`.
4. Inspect logs: `kubectl logs -l app=customer-edge | grep "upstream call to"` — the failing URL + exception class are logged.

### 401 on every authenticated route

1. Confirm the inbound token is from `openbank-customers` realm and not expired.
2. Confirm `QUARKUS_OIDC_TOKEN_ISSUER` matches the token's `iss` (the public KC host) — issuer is pinned independently of the JWKS-fetch URL.
3. Confirm JWKS is reachable at `QUARKUS_OIDC_AUTH_SERVER_URL` (in-cluster KC).

### Public onboarding route returns 401

`POST /onboarding/start` must be anonymous. If it 401s, verify `quarkus.http.auth.proactive=false` and that the route is served by `OnboardingResource` (no class-level `@RolesAllowed`), not `CustomerEdgeResource`.

### 403 on a read the customer expects to own

This is the IDOR guard working as designed: the `accountId` did not resolve to the JWT party. Confirm the account belongs to the calling party in account-service; a 403 is returned (not 404) deliberately, to avoid an existence oracle.

## Deploy / release

- Per-service path-scoped CI builds only when `openbank-customer-edge/src/main/**` changes; version is managed by release-please (`version.txt` = `0.9.0`; do not hand-edit).
- Image build → push to registry; ArgoCD picks up the new tag.
- The customer ingress applies a per-IP rate limit (`limit-rps`) as the first line of abuse defence (ADR-0065 / ADR-0069); deeper bot/spam hardening on `/onboarding/start` is an ADR-0069 Phase 2 follow-up.

## Tech stack

| Component | Version |
|---|---|
| Kotlin | per `libs.versions.toml` (fleet-wide) |
| Quarkus | 3.x (RESTEasy Reactive, OIDC, SmallRye Health/OpenAPI, config-yaml) |
| JDK runtime | 25 (Eclipse Temurin, ZGC) |
| HTTP client (outbound) | JDK `java.net.http.HttpClient` (HTTP/1.1) |

The exact pinned versions are emitted in the `/api/v1/info` payload at runtime.
