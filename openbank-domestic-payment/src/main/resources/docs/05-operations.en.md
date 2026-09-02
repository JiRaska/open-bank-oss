# Operations

## Build & run

```bash
# Build (fast-jar, never uber-jar)
./gradlew :openbank-domestic-payment:quarkusBuild

# Local gate before a PR
./gradlew :openbank-domestic-payment:detekt :openbank-domestic-payment:ktlintCheck \
          :openbank-domestic-payment:koverVerify :openbank-domestic-payment:build

# Docker (multi-stage; build stage JDK 20, runtime JRE 25 alpine, ZGC)
docker build -f openbank-domestic-payment/Dockerfile -t openbank-domestic-payment .
# or the generic helper:
openbank-infra/scripts/build-push-service.sh openbank-domestic-payment
```

> Always fast-jar (`-Dquarkus.package.jar.type=fast-jar`) and host-side build — the Dockerfile and the build-push helper already do this. The runtime stage runs as a non-root `openbank` user.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/domestic-payments/...` | 8116 | business REST API |
| `/api/v1/info` | 8116 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8116 | Swagger UI |
| `/q/openapi` | 8116 | OpenAPI spec |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) — management port |
| `/q/health` | 8085 | liveness + readiness (management port) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

The management interface is enabled on a **separate port 8085** (`quarkus.management.enabled=true`, root-path `/q`).

## Serverless tier (ADR-0057)

`domestic-payment` is a **money-path** service. Per ADR-0057, money-path hot paths are **Tier T0 (always-on)** — `minReplicas ≥ 1`, never scaled to zero: a synchronous payment hop must not eat a cold-start, and PSD2 expects payment availability. The tier is derived from measured behaviour and CI-checked against the declared tier; it is not hand-assigned here.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **must** be overridden in prod (Vault) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sanctions-service REST client (ADR-0032) |
| `AML_SERVICE_URL` | `http://localhost:8117` | aml-service REST client |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory vs enforce |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata in `/api/v1/info` |

Resilience knobs (`openbank.resilience.*`): circuit breaker (volume 15, ratio 0.5, success 5, delay 10s), retry (3, 500ms, jitter 200ms), timeout 15s. Outbox poll interval 5s. Rate limit 100 concurrent requests.

## Health checks

- **Liveness:** `/q/health/live` (port 8085) — pod restart on failure.
- **Readiness:** `/q/health/ready` (port 8085) — DB + Kafka producer + Redis.
- Flyway `connect-retries: 10` (2s interval) tolerates a slow DB at startup.

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% (T0 always-on) | `up{service="openbank-domestic-payment"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create incl. sync screening) | < 500 ms | includes sanctions-service round-trip |
| Outbox lag | < 10 s | PENDING age (poll 5s, batch 25) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Payments stuck in RECEIVED

Expected when screening returns REVIEW (potential hit ≤ 0.85) or when sanctions-service was unavailable (fail-closed). Action:
1. List held payments: `GET /api/v1/domestic-payments?status=RECEIVED`.
2. Inspect the AML case in `aml-service` (case opened with alert `AML_HOLD` / `SCREENING_UNAVAILABLE`).
3. Resolve via `PATCH /{id}/status` → `VALIDATED` (cleared) or `REJECTED` with the appropriate reason. Do **not** auto-release.

### Sanctions-service outage

Symptom: every create returns `RECEIVED`, AML cases with `SCREENING_UNAVAILABLE`. This is fail-closed by design. Restore sanctions-service, then re-screen held payments via the manual review flow. Check the REST-client circuit-breaker state.

### Outbox lag growing

1. `SELECT count(*) FROM domestic_payment_outbox WHERE status='PENDING';`
2. Check Kafka reachability and the dispatcher logs (`DomesticPaymentOutboxDispatcher`).
3. Inspect `FAILED` rows: `SELECT event_id, last_error, attempt_count FROM domestic_payment_outbox WHERE status='FAILED';` — the publish path is wrapped in a circuit breaker, so a downstream Kafka outage trips it and recovers automatically.

### Delegated-spend activation and recovery

The delegated-spend receiver is intentionally default-off. Activate it only after the
`openbank.delegation.spend-reservation-state` consumer has rebuilt from `earliest`, its lag is zero,
and a terminal revision followed by a delayed reserved revision remains terminal in the projection.
The compacted stream is revision-folded: apply the greatest payload `reservationVersion`, never the
last record observed.

For the request-fingerprint cutover, quiesce payment creation, drain the configured request timeout
(`openbank.domestic.resilience.timeout.value-ms`, currently 15 seconds), then switch every writer to
the healthy new image, and only then reopen creation. Use a blue/green switch, not a mixed-version
rolling interval: a request already accepted by an old instance and retried against a new one during
a rolling deploy is exactly the ambiguous-fingerprint case this cutover exists to close, not one it
can resolve after the fact. An old nullable fingerprint is deliberately a `409
IDEMPOTENCY_KEY_REUSED`, not a replayable authority. Never tell the caller to retry with a new key
for an ambiguous request — that mints a second payment for one intent. Inspect payment status and
require operator reconciliation before deciding whether it already went through.

Enable the finalizer last. It may release only a binding that domestic-payment atomically finalized
as absent; timeouts and unknown outcomes remain reserved. Its workflow-liveness signal must be
present while enabled. To roll back, stop new delegated creates, drain/reconcile every reserved
binding and outbox record, then disable the writer — never reintroduce Redis or an unprovable legacy
fingerprint as request authority.

### Illegal status transition (409)

The caller attempted a transition the state machine forbids (see [03 — API](./03-api.md)). Not retryable; fix the caller's target status.

## Deploy / release

- Per-service path-scoped CI (only changed services build).
- Release via release-please from Conventional Commits; do not hand-edit `version.txt` (current `0.3.0`) or `CHANGELOG.md`.
- **Money-path:** 2 approvals + threat model required (`docs/threat-models/openbank-domestic-payment.md`); never auto-merged.
- CD: ArgoCD picks up the image tag bump in the GitOps manifests.
