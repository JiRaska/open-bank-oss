# Operations

## Build & run

```bash
# Build (locally) — fast-jar, never uber-jar
./gradlew :openbank-aml-service:quarkusBuild

# Run dev mode (live reload, OIDC disabled in %dev)
./gradlew :openbank-aml-service:quarkusDev

# Local gate before a PR
./gradlew detekt ktlintCheck koverVerify build

# Container image (single-stage; the recipe is .github/workflows/Dockerfile.deploy)
#   build: host-side, ./gradlew build -Dquarkus.package.jar.type=fast-jar
#   runtime: eclipse-temurin:25-jre (glibc, #3354), non-root user, -XX:+UseZGC
```

> Build uses the host-side Gradle build, fast-jar packaging (`-Dquarkus.package.jar.type=fast-jar`), per the repo GitOps rules — never in-Docker Gradle, never uber-jar.

## Endpoints

| Path | Port | Purpose |
|---|---|---|
| `/api/v1/aml/cases/...` | 8117 | business REST API |
| `/api/v1/aml/cases/...` | 8443 | private-CA mTLS listener for in-cluster callers |
| `/api/v1/info` | 8117 | ServiceInfoResource (build metadata) |
| `/api/docs` | 8117 | Swagger UI |
| `/q/openbank/docs` | 8085 | **Docs-as-Service** (this documentation) |
| `/q/openapi` | 8117 | OpenAPI spec |
| `/q/health` | 8085 | liveness + readiness (SmallRye Health) |
| `/q/metrics` | 8085 | Prometheus (Micrometer) |

Management interface is on a **separate port 8085** (`quarkus.management.enabled=true`, root-path `/q`). It is disabled under `%test`.

In production, port 8443 requires a client certificate trusted by the private CA. Its server certificate and trust bundle are mounted from the `aml-service-internal-tls` Certificate; the service uses TLS 1.3. The listener is deployed before callers move to it. Port 8117 stays available for the existing readiness and admin UI paths during that transition. Verify that the Certificate is Ready and that 8443 accepts an authorized client before switching callers; if the listener fails, leave callers on 8117 and restore the previous deployment.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB password — **MUST be overridden in prod via Vault** |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret — Vault in prod |
| (datasource) | `postgresql://localhost:5432/openbank_aml` | reactive + JDBC URL |
| (kafka) | `localhost:29092` | Kafka bootstrap servers |
| (redis) | `redis://localhost:6379` | idempotency cache |
| (oidc) | `http://localhost:8080/realms/openbank` | OIDC issuer, client `openbank-services` |
| `OPA_URL` | `http://localhost:8181` | OPA sidecar PDP (ADR-0034) |
| `AUTHZ_ENFORCE` | `false` | flip OPA from advisory to enforce |
| `openbank.aml.auto-clear` | `false` | **sandbox-only** auto-clear of onboarding cases — keep `false` in prod |
| `BUILD_TIME` / `GIT_COMMIT` | `unknown` | build metadata surfaced in `/api/v1/info` |

Logging is JSON to console (`quarkus.log.console.json=true`), human-readable under `%dev`.

## Resilience

Configured under `openbank.resilience` and on the Kafka publisher:
- **Rate limit:** `max-concurrent-requests=100`.
- **Circuit breaker:** requestVolumeThreshold 10, failureRatio 0.5, successThreshold 5, delay 10s.
- **Retry:** maxRetries 2, delay 300ms, jitter 150ms. **Timeout:** 15s (request), 3s (Kafka publish).
- **Publisher:** `@Bulkhead(1,1)`, `@CircuitBreaker`, `@Retry`, `@Timeout(3000)`.

## Health checks

- **Liveness:** `/q/health/live` — JVM + ArC running. Pod restart on failure.
- **Readiness:** `/q/health/ready` — datasource + Kafka + Redis reachable.

Flyway runs at start (`migrate-at-start=true`, 10 connect retries × 2s) so a cold DB does not crash the pod immediately.

## SLO (targets)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target | Measurement |
|---|---|---|
| Availability | 99.9% | Prometheus `up{service="aml-service"}` |
| Latency p95 GET | < 100 ms | `http_server_requests_seconds{quantile=0.95}` |
| Latency p95 POST (create case) | < 300 ms | DB write + outbox insert |
| Outbox lag | < 10 s | oldest PENDING `aml_outbox` row age (dispatcher runs every 5s) |
| Error rate | < 0.1% 5xx | `http_server_requests_seconds_count{status=~"5.."}` |

## Serverless tier (ADR-0057)

`aml-service` has **no explicit declared tier** in `rules.yaml: finops.tiering.declared` and is not a money-path service, so it inherits the classifier-driven default (unclassified until measured). The outbox dispatcher is `@Scheduled` and the Deployment must run **`replicas: 1`** as the single outbox writer — so scale-to-zero is constrained by the dispatcher: any tiering must keep exactly one writer warm (the cold-start / single-writer guardrails in ADR-0057 apply).

## Runbooks

### Outbox lag growing

1. Count PENDING: `SELECT count(*) FROM aml_outbox WHERE status='PENDING';`
2. Check Kafka reachability: `kcat -L -b kafka:9092`.
3. Check dispatcher logs: `kubectl logs -l app=aml-service | grep AmlOutboxDispatcher`.
4. Confirm exactly one replica (single writer). If a row is stuck FAILED with retries exhausted it moves to DEAD — inspect `last_error`.

### Decision rejected with 409

Symptom: `409 conflict` (`InvalidAmlCaseStateTransitionException`). Cause: the requested `targetStatus` is not a legal transition from the current status, or the case is already terminal (`CLEARED`/`BLOCKED`). Action: re-fetch the case, choose a valid transition; terminal cases cannot be reopened.

### Onboarding cases not clearing in sandbox

Check `openbank.aml.auto-clear` — it is `false` by default. In production this is intentional (analyst decision required). In sandbox, set it `true` to let `PartyEventConsumer` auto-clear onboarding cases.

### Flyway checksum mismatch on startup

Never edit an applied migration. If a checksum mismatch blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the GitOps env, let the DB settle, then remove it.

## Deploy / release

- Per-service CI pipeline (path-scoped): test → fast-jar build → SBOM → image build/push → ArgoCD picks up the tag.
- **Version bump:** any change under `src/main/**` bumps `version.txt` per commit type — but release-please owns the release axis; do not hand-bump `version.txt` against an open Release PR.
- For image-tag merge conflicts in GitOps take `--ours` (the freshly built tag), never blind `--theirs`.

## Tests

- Unit: `AmlCaseServiceTest`, `AmlCaseTest` (state machine), `AmlOutboxDispatchTest`.
- Integration: `AmlOutboxDispatchIT` with `PostgresRedisTestResource` (Testcontainers — isolated Postgres + Valkey per test JVM, CI infra sweep #578). Under `%test` the scheduler is disabled so the IT drives `dispatchScheduledBatch()` explicitly.
- Coverage is ratchet-only (Kover, ADR-0020) — never lower it.

## Internal mTLS listener

In production the AML service requires a client certificate on its TLS listener at port 8443. The existing HTTP listener at port 8117 remains enabled for readiness and Admin UI discovery; it is a separate path from caller mTLS. During rollout, verify that callers present certificates from the trusted private CA to 8443 and that readiness still probes 8117. A ready pod or a successful HTTP probe does not establish that the client-authenticated listener works. The client-auth requirement is build-time Quarkus configuration, so it belongs to the production profile rather than a deployment environment override.

The generated ingress policy now grants migrated caller namespaces only port 8443. Admin UI and the security scanner retain port 8117; traffic within the AML namespace remains allowed. A caller that still needs HTTP must be identified before tightening its namespace rule.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Transaction-monitoring intake (ADR-0371)

- Channels `ledger-events-in` (`openbank.ledger.journal.posted`) and `transaction-events-in` (`openbank.transactions.transaction.initiated`), consumer group `openbank-aml-service`. Each rethrows what it cannot record; the connector parks the record on `openbank.dlq.aml.<channel>`.
- `openbank.aml.monitoring.attribution.*`: `enabled`, `interval`, `initial-delay`, `batch-limit`, `origin-grace`.
- Metrics: `openbank_aml_monitoring_booked_movements_total{outcome}`, `openbank_aml_monitoring_transaction_origins_total{outcome}`, `openbank_aml_monitoring_movements_uncounted`, `openbank_aml_monitoring_movements_oldest_uncounted_age_seconds`, and the workflow-liveness gauges for `aml-movement-attribution`.
- A growing `movements_uncounted` with a live sweep means accounts are not resolving to a party: check the account authority, then the rows with `last_attempt_at` set and `counted_at` null.

## Transaction-monitoring evaluation (ADR-0371)

- `openbank.aml.monitoring.evaluation.*`: `enabled`, `cron`, `page-size`, `open-cases` (default `false` = SHADOW).
- Metrics: `openbank_aml_monitoring_evaluations_total{outcome}`, `openbank_aml_monitoring_hits_total{scenario}`, `openbank_aml_monitoring_cases_opened_total{scenario}`, `openbank_aml_monitoring_cases_oldest_undecided_age_seconds`, and the workflow-liveness gauges for `aml-monitoring-evaluation`.
- Liveness is recorded only by a tick that reached every active party. A stale gauge while movements are being counted means evaluation is not running.

## Held-payment case intake (ADR-0374)

- Channel `payment-held-in` (env `AML_PAYMENT_HELD_CONSUMER_ENABLED`, **off by default**) reads the event topic of each payment rail that has adopted the admission chain, today `openbank.domestic.payment.events`, and opens one case per held payment from the rail's `PAYMENT_HELD` event. Every other event type on the topic is ignored.
- The case: screening type `TRANSACTION_MONITORING`, risk `HIGH` (so it opens `UNDER_REVIEW`), alert code `PAYMENT_HELD`, idempotency key `payment-hold:<rail>:<paymentId>`, `transactionId` = the payment. The alert detail lists why the payment is held, when the hold expires and what each control answered.
- Deciding the case ends the hold on the rail: `CLEARED` releases the payment, `BLOCKED` rejects it. `ESCALATED` and `UNDER_REVIEW` leave it waiting, and it is then rejected when the hold expires.
- Opening the case depends only on this service's own database. A rail that could not read the debtor's standing sends no party; the intake does not ask the account authority, it opens the case with the party unset (`party_id IS NULL`, `partyResolved: false` on the API and on both case events, where the required `partyId` field then carries the all-zero UUID, which identifies nobody). The account id is never filed as the party.
- The party-resolution sweep fills the party in from the case's account once the account authority answers. A lookup that does not succeed (authority unreachable, an error answer, an account it does not know) stamps `party_resolution_attempted_at`, and each tick takes the least recently attempted cases first, so a case that cannot be resolved does not hold up the ones behind it. `openbank_aml_cases_party_unresolved` is the backlog; `GET /api/v1/aml/cases?partyResolved=false` lists it. A case can be decided while its party is unresolved.
- Until a case is resolved it is outside everything keyed by party: the `?partyId=` list filter, erasure on `PARTY_ERASED`, and a suspicious-transaction report for that party. A party erased while one of its cases was still unresolved is not caught up automatically.
- Only an event that cannot be read (including one that names no debtor account, or a case key other than its own) and a failed write to the case store go to `openbank.dlq.aml.payment-held-in`. A record there is a payment held with no case: replay it or decide the payment on the rail before its hold expires.
- The key namespace `payment-hold:` is reserved: `POST /api/v1/aml/cases` refuses it (and `tm:`, `quarantined:`) with 400. A reserved key is not what makes a case the hold's case, though. Every case records which entry point opened it (`origin`, set by the service and never by a caller), and the intake accepts under a hold key only a case whose origin is its own. A case of any other origin found there is moved to the key `quarantined:<its case id>`, the intake opens the real case under the hold key in the same transaction, `openbank_aml_payment_held_foreign_key_claims_total` is incremented and an ERROR line names both cases. Until that happens such a case cannot be decided (`PUT /decision` answers 409), so no decision event can leave under a hold key from a case the intake did not open.
- Any increment of `openbank_aml_payment_held_foreign_key_claims_total` deserves a look at who created the displaced case and whether it was decided before the reservation existed: a decision published earlier under the hold key cannot be recalled from here, and whether the rail acted on it is the rail's record to check.
- Metric: `openbank_aml_payment_held_events_total{outcome}` with `case_opened`, `ignored` (another event type) and `rejected`.
- Rolling out: a build from before unresolved parties could be stored cannot read a case without a party. Turn the channel on only when every running pod is on a build that can.
- Switch it on together with the rail's chain, never the rail alone: a hold whose case is never opened ends by expiry, in rejection.
