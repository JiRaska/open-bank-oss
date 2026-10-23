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

### Durable outbound booking clock

Acceptance into `SENT_TO_CLEARING` records the first booking-due timestamp in the same locked, revision-checked transaction as status and its outbox event, before the settlement activity. Retries preserve that timestamp and the existing `domestic-settlement-<paymentId>` key. Only a strictly matched completed transaction response with an identity permits the booking-proof timestamp and transaction identity to commit atomically with `SETTLED` and its outbox event. Generic status updates cannot clear a pending clock or certify coverage.

`openbank_payment_booking_tracked_pending_oldest_age_seconds{rail="DOMESTIC_CZK"}` reports the age of the oldest tracked pending marker across business statuses. It is a lower bound when coverage is incomplete, starts as NaN before a successful query, and is zero when the successfully queried tracked set is empty. A failed refresh retains the last durable timestamp and its age keeps increasing. `openbank_payment_booking_unknown_coverage{rail="DOMESTIC_CZK"}` separately counts rows without a start/proof whose coverage is unknown. Existing rows in every lifecycle state retain nullable unknown provenance without backfill; a generic write bypassing booking tracking also remains unknown across later terminal states. This count is conservative historical coverage, not proof that each counted row is unsettled. An empty tracked set does not establish all-clear while coverage is unknown.

These clocks are separate from payment creation, clearing dispatch, refunds and the existing stranded gauge. No age threshold, SLA alert, historical binding, new retry key, lookup/recovery API or retention policy is introduced. Historical reconciliation and owner-approved SLA rules remain open.


### Unsettled internal booking outcomes

`openbank_payment_unsettled_total{rail="DOMESTIC_CZK",outcome="..."}` counts unsuccessful transaction-service attempts. Its Micrometer name is `openbank.payment.unsettled`. The bounded outcomes are `mismatch`, `failed-replay`, `conflict`, `unexpected-status` and `transport-error`; five zero series are registered before any attempt. Retries count as separate attempts, not unique payments. Cancellation and matched successful responses do not increment it. `failed-replay` denotes a decoded FAILED response, not proof that this call was a replay. The metric rail label is `DOMESTIC_CZK`; the transaction request still uses `rail=DOMESTIC` and `instructionType=ONE_OFF`.

Settlement requires HTTP 201, a COMPLETED transaction with an id, and a match to the submitted type, account identities, amount, currency, rail and instruction type. A failed or uncertain attempt does not advance the payment from SENT_TO_CLEARING to SETTLED. The existing provider resilience and Temporal activity policies may retry; this counter does not change those policies or count the outer exception wrapper again. Preserve `domestic-settlement-<paymentId>` during investigation: a 409 or uncertain response is not proof that no booking occurred. Establish the transaction outcome through approved reconciliation tooling before corrective action; do not rotate the key or use a new booking POST as a lookup.

The attempt counter alone does not establish durable age or reconciliation. The separate tracked booking clock above adds partial durable coverage, not a public transaction lookup by idempotency key, an age/SLA alert or a recovery endpoint. The existing stranded-payment gauge remains unchanged and does not establish those missing guarantees. These parts of #13623 remain open; neither a zero counter nor a successful local build proves reconciliation or deployment readiness.

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

## AML service mTLS in production

The production AML REST client uses the named `aml-authority` TLS configuration and the AML service's client-authenticated listener on port 8443. The deployment supplies a client certificate and the private CA trust bundle; TLS 1.3 is required. If AML calls fail after rollout, check the client certificate and CA mount, the production REST-client URL, and the network policy path to that listener together. Local development and tests continue to use their HTTP fixtures; a passing local HTTP test alone does not prove the deployed TLS handshake.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
