# Operations

## Build & run

```bash
# Build (locally, host-side Gradle — never in-Docker)
./gradlew :openbank-standing-order-service:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-standing-order-service:quarkusDev

# Container image (fast-jar, never uber-jar)
openbank-infra/scripts/build-push-service.sh standing-order-service
```

The Dockerfile uses `-Dquarkus.package.jar.type=fast-jar` and the runtime stage COPYs `quarkus-app/` (repo rule — uber-jar leaves it empty and crashloops).

## Endpoints & ports

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/standing-orders/...` | 8121 | business REST API |
| `/api/v1/info` | 8121 | ServiceInfoResource (build metadata, `openbank-libs`) |
| `/api/docs` | 8121 | Swagger UI (`swagger-ui.path`) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8121 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (management interface) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

The **management interface is enabled** (`quarkus.management.enabled=true`) on port **8085**, root-path `/q`, host `0.0.0.0`. The dev UI is disabled.

## Configuration

| Config / env var | Default | Purpose |
|---|---|---|
| `quarkus.http.port` | `8121` | app port |
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| reactive datasource URL | `postgresql://localhost:5432/openbank_standing_orders` | DB |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| OIDC auth-server-url | `http://localhost:8080/realms/openbank` | issuer |
| kafka bootstrap-servers | `localhost:29092` | brokers |
| redis hosts | `redis://localhost:6379` | Valkey |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar base URL |
| `OPA_PATH` | `/v1/data/openbank/rest/allow` | OPA query path |
| `OPA_TIMEOUT_MS` | `500` | OPA decision timeout |
| `AUTHZ_ENFORCE` | `false` | advisory vs enforce (ADR-0034) |
| `openbank.outbox.poll-interval` | `5s` | dispatcher cadence |

Security headers (CSP, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy) are set in `application.yaml`. CORS allows `http://localhost:3000` with `Idempotency-Key` among allowed headers.

## Health checks

- **Liveness:** `/q/health/live` (management port 8085) — JVM + ArC.
- **Readiness:** `/q/health/ready` (management port 8085) — DB / Kafka / Redis wiring (SmallRye Health).

```yaml
livenessProbe:
  httpGet: { path: /q/health/live, port: 8085 }
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /q/health/ready, port: 8085 }
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Serverless / scale-to-zero tier (ADR-0057)

Standing-order is **not** a money-path service and its traffic is bursty (admin/customer-driven creation, no real-time hot path). It is a candidate for a **scale-to-zero / low-replica tier** per ADR-0057. Caveat: the outbox dispatcher is a `@Scheduled` in-process loop — if the pod is scaled to zero, outbox draining pauses until a request (or a keep-warm probe) wakes it. Keep at least a warm replica if outbox latency matters, or move dispatching to an always-on tier. (Exact tier assignment: TBD — confirm against the ADR-0057 rollout matrix.)

## SLO (targets)

| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="standing-order-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create) | < 300 ms | DB write + outbox insert |
| Outbox lag | < 30 s | age of oldest PENDING/processable outbox row |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Outbox not draining

1. Count processable rows: `SELECT count(*) FROM standing_order_outbox WHERE status='PENDING'`.
2. Check the dispatcher is firing: `kubectl logs -l app=standing-order-service | grep OutboxDispatcher`. It runs every 5 s with `SKIP` concurrency.
3. Check the circuit breaker: persistent failures trip `@CircuitBreaker` (volume 10, ratio 0.5, 5 s open). Inspect `last_error` on failed rows.
4. Verify Kafka reachability and the topic `openbank.standing-orders.order.event`.
5. If the pod was scaled to zero (ADR-0057 tier), the loop only resumes when the pod is up — see the serverless caveat above.

### Illegal state transition (pause/resume/cancel rejected)

Symptom: a 422/error from a domain `require`. Cause: e.g. pausing a non-ACTIVE order, resuming a non-PAUSED order, or cancelling a CANCELLED/COMPLETED order. Action: re-fetch the order (`GET /{id}`), reconcile the client's view of `status`.

### Duplicate create

A repeated `idempotencyKey` returns the **existing** order (not an error). If a client sees an unexpected "old" order, it reused a key — generate a fresh `idempotencyKey` per logical request.

## Tech-stack version matrix

Versions are resolved from the shared `libs.versions.toml` / `openbank.quarkus-service` convention plugin and surfaced in `/api/v1/info` at runtime (authoritative source). Indicative stack:

| Component | Version |
|---|---|
| Kotlin | 2.x (shared catalog) |
| Quarkus | 3.x LTS (shared catalog) |
| JDK runtime | 21+ (Eclipse Temurin) |
| PostgreSQL | 16 |
| Kafka client | 3.x |

> Exact pinned versions are not hard-coded in this service's `build.gradle.kts` (it inherits the BOM and the convention plugin) — read `/api/v1/info` or the root `libs.versions.toml` for the precise numbers.

## Deploy / release

- **Release axis:** release-please owns `version.txt` (currently `0.2.0`). A feature/fix PR must **not** hand-edit `version.txt`; merging to `main` opens a per-service Release PR which bumps it, writes the changelog and tags `standing-order-service-v<version>`.
- **API contract axis:** `openapi.yaml info.version` (`1.0.0`) is bumped independently from the OpenAPI diff (ADR-0048), not from the commit type.
- **CI:** path-scoped per-service pipeline; integration tests use per-job Testcontainers (no shared compose stack).
