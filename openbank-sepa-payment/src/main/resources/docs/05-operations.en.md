# Operations

## Build & run

```bash
# Build (locally) — fast-jar (never uber-jar)
./gradlew :openbank-sepa-payment:quarkusBuild

# Run dev mode (live reload; OIDC disabled in %dev)
./gradlew :openbank-sepa-payment:quarkusDev

# Local gate before a PR
./gradlew :openbank-sepa-payment:detekt ktlintCheck koverVerify build

# Generic image build (host-side gradle, then Docker COPY of quarkus-app/)
openbank-infra/scripts/build-push-service.sh sepa-payment
```

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/sepa-payments/...` | 8115 | business REST API |
| `/api/v1/info` | 8115 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8115 | Swagger UI (`quarkus.swagger-ui.path`) |
| `/q/openapi` | 8085 | OpenAPI spec (management) |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

> The management interface is enabled on a separate port `8085` with root-path `/q` (`quarkus.management.*`).

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — **prod via Vault** |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka brokers |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sanctions-service REST client (screening gate) |
| `AML_SERVICE_URL` | `http://localhost:8117` | aml-service REST client (case opening) |
| `OPA_URL` / `OPA_PATH` / `OPA_TIMEOUT_MS` | `http://localhost:8181` / `/v1/data/openbank/rest/allow` / `500` | OPA sidecar (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | OPA advisory→enforce switch |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata for `/api/v1/info` |

The DB is reached via both reactive (`postgresql://…/openbank_sepa_payments`) and JDBC (Flyway) URLs. Redis at `redis://localhost:6379` backs idempotency.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — DB pool + Kafka producer + Redis.

Note for the **screening gate**: the sanctions/AML REST clients are wrapped in MicroProfile Fault Tolerance (circuit breaker, retry, timeout — see `openbank.resilience.*`). A sanctions outage does **not** fail readiness; it makes `createPayment` fail-closed (payment held in `RECEIVED`).

## FinOps tier (ADR-0057)

**T0 — Always-on.** As a money-path synchronous hop, sepa-payment must not eat a cold-start, so `minReplicas ≥ 1`; it never scales to zero. The tier is derived from measured behaviour and gated declared-vs-measured in CI.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="openbank-sepa-payment"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create incl. sync screen) | < 800 ms | includes DB write + 2× sanctions screen call |
| Outbox lag | < 10 s | age of oldest PENDING `sepa_payment_outbox` row |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Runbooks

### Unsettled outbound booking attempts

`openbank_payment_unsettled_total{rail="SEPA",outcome="..."}` uses the Micrometer name `openbank.payment.unsettled`. Its five bounded outcomes are `mismatch`, `failed-replay`, `conflict`, `unexpected-status` and `transport-error`; all five series are registered at zero. Each unsuccessful provider attempt increments once, including separate retries; this is not a unique-payment count. Cancellation and matched successful responses increment nothing. `failed-replay` means a decoded FAILED response, not proof that this request was replayed. Labels contain no payment, account or user identities or provider exception text. Zero means no observed unsuccessful attempts since process start, not evidence that a booking completed.

Outbound settlement requires HTTP 201 and a COMPLETED transaction with an id whose type, source account, numeric amount, currency, rail and instruction type match the submitted request. The structured request maps `SCT` to `SEPA_CT` and `SCT_INST` to `SEPA_INST`, with `ONE_OFF` for both. These transaction fields do not themselves prove clearing completion or an instant-payment SLA. The existing `sepa-settlement-<payment-id>` key is unchanged. An existing booking under that key may still carry historical `SEPA_CT` or a null instruction type; a mismatched response remains unresolved. Do not infer that no old booking exists from today's strict request parsing, accept a mismatched booking, change its recorded history or submit a new key to force a retry. Establish the outcome through an approved reconciliation read path before corrective action.

The counter supplies no durable outbound-booking age, SLA threshold/alert or new recovery endpoint. The refund clock below measures a different operation. A public read-only transaction lookup by idempotency key and the remaining booking clocks, alerts and operator integration remain open parts of #13623.

### Unresolved inbound returns (#13623)

A return is terminal `RETURNED` only after transaction-service proves a completed reversal with
its transaction ID. HTTP 503 means reconciliation is still required, not proof that no credit
occurred. The original payment row keeps the first return receipt, original transaction identity,
server-derived actor/correlation and reason; retries do not replace these values. The internal
`return_first_received_at` / `return_resolved_at` pair measures pending-refund age independently
of payment creation. These columns are not a new public payment status.

Reconcile against transaction-service using `sepa-reversal-<original-payment-id>` and the original
transaction ID. Check the persisted reversal outcome before repeating the original return. Never
create a new payment or reversal key to clear an uncertain outcome. A missing original transaction
ID is `UNRESOLVED_ORIGINAL_TRANSACTION`; establish the original ledger identity through the existing
reconciliation process before any further financial action. Absence of an ID does not prove no debit.
There is no automatic rebooking, new operator endpoint, alert or recovery scheduler in this change.
Legacy `RETURNED` rows are not retroactively certified refunded by this migration.

The current transaction REST API has no read-only lookup by idempotency key. This change does not
supply that missing operator integration or authorise a POST as a lookup. Use only an existing
approved reconciliation read path; without one, key-based operator recovery remains blocked.

API-contract note: `POST /api/v1/sepa-payments/returns` documents HTTP 409 when a state or persistence
conflict prevents the return/evidence commit, and HTTP 503 when reversal completion remains unproven.
Neither response proves that the ledger reversal did not occur. Preserve the original payment identity
and reversal key while reconciling. The ADR-0048 classifier with oasdiff 1.22.0 classifies these added
responses as additive: `1.16.0` → `1.17.0`; the served `/api/v1` major is unchanged. This contract axis
is independent of the automatically managed release version.

While a return intent is unresolved, conflicting terminal transitions are refused even when a
writer has read a fresh aggregate revision. General aggregate writes cannot clear the intent,
rewrite its request metadata or mark it resolved without the atomic return evidence. The database
also constrains pending intents to the existing `PROCESSING`/`COMPLETED` recovery states.

An initial settlement response may arrive after a return intent has committed without an original
transaction ID. Only the workflow's explicit settlement-completion operation may attach that first
ID after the existing adapter has validated the response against its exact original request.
That short transaction reloads the latest aggregate, preserves the return receipt and metadata,
and commits the attached identity and completion outbox row together. A compatible late callback
after completion/return emits no duplicate event; a conflicting identity or terminal state is refused.
This is not a historical identity lookup or general operator/PATCH permission to rewrite an intent.

The refund-only `openbank_payment_refund_unsettled_oldest_age_seconds` gauge has fixed labels
`rail="SEPA"` and `operation="refund"`. It reads the earliest unresolved durable return receipt,
not payment creation, outbound-booking start or clearing age. Before the first successful query it
is `NaN` (unknown); a successful empty query yields zero. Query failures preserve the last successful
timestamp/empty snapshot and do not record refresh liveness success. Cached timestamps continue to
age with the clock during a refresh outage. This change supplies no refund SLA threshold or alert.

### Payments stuck in RECEIVED

This is **by design** when screening returns REVIEW or is unavailable (fail-closed, ADR-0032).
1. Check whether the sanctions service is healthy: `curl $SANCTIONS_SERVICE_URL/q/health/ready`.
2. List held payments: `SELECT payment_id, status, created_at FROM sepa_payments WHERE status='RECEIVED' ORDER BY created_at`.
3. Check the matching AML cases in aml-service (alert codes `AML_HOLD` / `SCREENING_UNAVAILABLE`).
4. A held payment is released or rejected through the AML case lifecycle / a `PATCH …/status` transition, never automatically.

### Outbox lag growing

1. `SELECT count(*) FROM sepa_payment_outbox WHERE status='PENDING'`.
2. Check Kafka reachability and the dispatcher logs (`SepaPaymentOutboxDispatcher`).
3. Inspect `last_error` / `attempt_count` on FAILED rows; the circuit breaker may be open after repeated publish failures.

### Idempotency replay

A repeated `POST` with the same `Idempotency-Key` returns the cached response with `X-Idempotency-Replayed: true`. A blank key returns `400`. Do not "fix" by reusing a key with a different body — generate a new key per logical payment.

### DB / migrations

Never modify an applied migration (checksum mismatch → startup fail). For a settled live DB use `QUARKUS_FLYWAY_REPAIR_AT_START=true`, then remove it.

## Tech-stack version matrix

Resolved from `libs.versions.toml` at build, surfaced in `/api/v1/info`:

| Component | Version |
|---|---|
| Kotlin | 2.x |
| Quarkus | 3.x LTS |
| JDK runtime | 25 (Eclipse Temurin) |
| PostgreSQL | 16 |
| Hibernate | Reactive (Panache) |

> Exact pinned versions are owned by `openbank-libs` `libs.versions.toml`; this service inherits them via the `openbank.quarkus-service` convention plugin.

## Deploy / release

Per-service CI pipeline + release-please (per-service component, `version.txt`). As a **money-path** service, a merge needs **2 approvals + an up-to-date threat model**. Image builds are fast-jar, host-side gradle then Docker COPY of `quarkus-app/`. CD via ArgoCD picks up the new image tag from the GitOps manifest.

## AML service mTLS in production

The production AML REST client uses the named `aml-authority` TLS configuration and the AML service's client-authenticated listener on port 8443. The deployment supplies a client certificate and the private CA trust bundle; TLS 1.3 is required. If AML calls fail after rollout, check the client certificate and CA mount, the production REST-client URL, and the network policy path to that listener together. Local development and tests continue to use their HTTP fixtures; a passing local HTTP test alone does not prove the deployed TLS handshake.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
