# Operations

## Build

```
./gradlew :openbank-transaction-service:build       # compile + test
./gradlew detekt ktlintCheck koverVerify build      # local gate before a PR
```

CI is path-scoped (only changed services build). The domain layer has **zero** framework imports. Coverage gate (Kover): line coverage ≥ 40 (`build.gradle.kts`); ratchet-only, never lower. Money-path services aim higher.

### Image

- **fast-jar only** — the Dockerfile COPYs `quarkus-app/`. Never `uber-jar` (leaves `quarkus-app/` empty → crashloop).
- **Host-side build, not in-Docker Gradle.** Generic build: `openbank-infra/scripts/build-push-service.sh transaction-service`.

## Configuration (key env)

| Env | Purpose | Default (dev) |
|---|---|---|
| `POSTGRES_PASSWORD` | DB credential | `CHANGE_ME_LOCAL_DEV_ONLY` |
| `OIDC_CLIENT_SECRET` | Keycloak client secret (OIDC + oidc-client) | placeholder |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | realm URL | `http://localhost:8080/realms/openbank` |
| `LEDGER_SERVICE_URL` | ledger REST client | `http://localhost:8101` |
| `BALANCE_SERVICE_URL` | balance REST client | `http://localhost:8103` |
| `FX_SERVICE_URL` | fx REST client | `http://localhost:8119` |
| `BUILD_TIME` / `GIT_COMMIT` | build metadata for `/api/v1/info` | `unknown` |

Ports: **8102** app, **8085** management (`/q`). Rate limit: `openbank.rate-limit.enabled=true`, `max-concurrent-requests=150`. Outbox poll: every 5 s, initial delay 5 s.

## Health probes

SmallRye Health is on the management interface (`/q`, port 8085):

- **Liveness:** `/q/health/live`
- **Readiness:** `/q/health/ready` (DB connectivity)
- **Metrics:** `/q/metrics` (Micrometer / Prometheus)
- **Docs:** `/q/openbank/docs` (this documentation)

OpenTelemetry traces export to OTLP `http://localhost:4317` (`service.name = openbank-transaction-service`).

## Serverless tier (ADR-0057)

Transaction-service is a **money-path** service, therefore **T0** by the `t0_baseline: money_path_services` rule (`rules.yaml`). T0 = always-warm, `min > 0` replicas. It is **not** a scale-to-zero candidate: it sits on the synchronous payment path (a cold start would add to a chain of sync ledger/balance calls). Demoting it below T0 requires an ADR-0030 threat-model update + 2 approvals.

## SLO

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target (proposed) | Notes |
|---|---|---|
| Availability | 99.9% | money-path |
| Initiate p95 latency | TBD | dominated by synchronous ledger + balance + (optional) FX calls |
| Outbox publish lag | < 10 s | dispatcher runs every 5 s |
| RTO / RPO | 15 min / 5 min | inherited platform target (see account-service pilot) |

Exact latency SLO numbers are TBD until measured in the sandbox; the saga's downstream calls have 2 s connect / 3 s read timeouts (`application.yaml`).

## Runbooks

### Outbox stuck (events not publishing)
1. Check `transaction_outbox` for rows with `status='FAILED'` and `last_error`.
2. The dispatcher wraps publish in a `@CircuitBreaker` — a tripped breaker pauses publishing; inspect logs for the breaker state and Kafka connectivity.
3. After Kafka recovers, FAILED rows are retried on the next poll (re-listed by `listProcessable`).

### Saga stuck in-flight
1. Query `payment_sagas WHERE state NOT IN ('COMPLETED','COMPENSATED','FAILED')` (covered by the partial index).
2. A saga that threw mid-flight should already be COMPENSATED/FAILED (compensation is best-effort and idempotent). A row left in FUNDS_RESERVED indicates a hold was placed — balance-service expires it after the 300 s TTL, so no funds leak.
3. Verify ledger consistency: a posted journal is reversed during compensation; a captured debit is refunded (`compensation-{txId}`).

### Flyway checksum mismatch on startup
- Never rewrite an applied migration. If a checksum mismatch blocks startup, set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the gitops env, let the DB settle, then remove it.

### `transactions` partition horizon (#17101)
- `transactions` is RANGE-partitioned by `booking_date` per year. V25 pre-creates 2027/2028; `TransactionPartitionMaintainer` (`suspend` `@Scheduled`, `every: 24h`, cluster-locked) keeps current year + `openbank.transaction.partition.future-years` (default 2) created. Retention is audited only, never executed.
- Every action is recorded in append-only `partition_lifecycle_audit`. A `DEFAULT_NONEMPTY` row means bookings landed in `transactions_default`: the matching year partition can then no longer be created until those rows are moved out in a maintenance window. V25 fails the migration with an explicit message in that state instead of moving data.
- Partition DDL runs under `SET LOCAL lock_timeout` (`openbank.transaction.partition.lock-timeout`, default `5s`, must be > 0). `CREATE TABLE ... PARTITION OF` needs ACCESS EXCLUSIVE on `transactions`; a long transaction holding a lock on it makes the DDL fail with SQLState 55P03 after the timeout rather than queueing every booking behind it. The pass records no success (`transaction-partition-maintenance` liveness alert) and the next tick retries.
- A dead maintainer shows as `WorkflowLivenessStale` on `openbank_workflow_*{workflow="transaction-partition-maintenance"}`.

### FX rate unavailable
- Cross-currency initiate fails with `FxRateUnavailableException` when fx-service has no quote. Confirm `FX_SERVICE_URL` and fx-service health; same-currency settlements are unaffected (no FX leg).

### Rail settlement has no Kafka path
- Settlement from the payment rails reaches this service only over REST (sepa-payment `SettlementAdapter` calls transaction-service, ADR-0108). The former `payment.scheme-accepted` consumer, its channel, topic, DLQ and msg-override ConfigMap were removed on 2026-10-17 because no service ever produced to that topic. If a settlement is missing, look at the calling rail's REST client and the OIDC/OPA decision, not at Kafka lag or a DLQ.

### Synthetic transfer origin

Transaction initiation stores the verified synthetic origin on the transaction and its outbox event.
The payment worker reloads that value before selecting the ledger posting route and carries it into
terminal events and reversals. Reusing an idempotency key across synthetic and ordinary origins
returns HTTP 409. Existing transactions have ordinary origin.

Before configuring a synthetic transfer journey, verify that every payment worker can read the new
column and that ledger serves the synthetic posting route for the trusted transaction workload.
During rollback, stop synthetic initiation and drain pending synthetic workflows with a compatible
worker before running a version that does not understand the origin column.

## Deploy

GitOps via ArgoCD (sandbox tracks the deploy branch). For image-tag merge conflicts in gitops manifests, take `--ours` (the freshly-built tag), never blind `--theirs`. Verified-signature ruleset requires the commit email match the registered GPG key.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Synthetic own-account transfer admission (#7284)

`POST /api/v1/transactions/synthetic-transfer` requires the verified `openbank-edge` workload
and the request filter's synthetic decision. It derives a fixed CZK 0.01 transfer and the
own-account exemption from the fixture request. The party, current Europe/Prague booking date
and direction form the daily idempotency key. The Edge sends that key as `Idempotency-Key`
and Transaction rejects any mismatch. A new outbound booking closes 305 seconds before bank
midnight, allowing the 300-second canary deadline plus a 5-second HTTP call. Same-day
returns can still book in that window. The durable origin controls the synthetic ledger route.

## Client certificate reload (outbound mTLS)

Every named `quarkus.tls.<name>` configuration that presents a client certificate to a peer's
8443 listener sets `reload-period: 1h`. cert-manager renews the mounted client certificate
before it expires; without a reload period the process would keep presenting the old one until
the peer rejects it, while every expiry signal measured the renewed Secret and read healthy.
After a renewal the new certificate is in use within an hour, with no restart. The
`tls-certificate-reload` gate keeps every such configuration covered.
