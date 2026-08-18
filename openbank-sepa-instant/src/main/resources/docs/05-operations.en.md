# Operations

## Build

```
./gradlew :openbank-sepa-instant:build
./gradlew detekt ktlintCheck koverVerify build   # the local gate before a PR
```

Built via the `openbank.quarkus-service` convention plugin (ADR-0049 D1). Kover line-coverage floor **40%** (money-path baseline, ratchet-only; target 70%).

## Image & deploy

- **Always fast-jar, never uber-jar** — the Dockerfile COPYs `quarkus-app/`; uber-jar leaves it empty → crashloop (repo gotcha).
- Host-side build, not in-Docker Gradle: `openbank-infra/scripts/build-push-service.sh openbank-sepa-instant`.
- Deployed via GitOps/ArgoCD.

## Serverless tier (ADR-0057)

**T0 — Always-on.** sepa-instant is in `rules.yaml: money_path_services`, and the submit path is a **synchronous money hop where cold-start latency is unacceptable** (sub-10s settlement). Per [ADR 0057](../../../../docs/adr/0057-scale-to-zero-workload-tiers-and-finops-classifier.md): `minReplicas ≥ 1`, **never scales to zero**, full availability. A T0→lower demotion would need an ADR-0030 threat model + 2 approvals — money path is sacred.

## Ports & endpoints

- **App:** 8127 (HTTP). Security headers set globally (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, nosniff, etc.).
- **Management:** 8085, root path `/q` (health, metrics, docs).
- Swagger UI: `/api/docs`.
- Docs-as-Service: `/q/openbank/docs` (ADR-0019).

## Health probes

SmallRye Health (`quarkus-smallrye-health`) on the management port:
- `/q/health/live` — liveness.
- `/q/health/ready` — readiness (includes the reactive datasource).

## Observability

- **Metrics:** Micrometer → Prometheus (`/q/metrics`).
- **Tracing:** OpenTelemetry OTLP → `:4317` (resource attribute `service.name = openbank-sepa-instant`).
- **Logs:** JSON console (`quarkus.log.console.json = true`) in non-dev; plain text in `%dev`.

## SLO (money-path, indicative)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Indicator | Target |
|---|---|
| Submit→decision latency (incl. screening) | < 10 s (execution-timeout = 10 s) |
| Availability | T0 always-on; aligns with money-path continuity |
| RTO / RPO | 15 min / 5 min (platform default, see ADR-0057/DORA) |

## Configuration knobs

| Property | Default | Meaning |
|---|---|---|
| `openbank.sct-inst.execution-timeout-seconds` | 10 | watchdog deadline armed when a payment goes PROCESSING |
| `openbank.sct-inst.recall-window-days` | 10 | recall window |
| `openbank.rate-limit.max-concurrent-requests` | 500 | concurrency cap |
| `openbank.resilience.circuit-breaker.*` | vol 20 / ratio 0.3 / succ 10 / 5 s | screening-hop breaker |
| `openbank.resilience.retry.*` | 2 / 100 ms / 50 ms jitter | retry |
| `openbank.resilience.timeout.value-ms` | 10000 | call timeout |
| `AUTHZ_ENFORCE` | false | OPA advisory vs enforce (ADR-0034) |
| `SANCTIONS_SERVICE_URL` | `http://localhost:8123` | sanctions REST client |
| `AML_SERVICE_URL` | `http://localhost:8117` | aml REST client |

Secrets (`POSTGRES_PASSWORD`, `OIDC_CLIENT_SECRET`) come from the environment; the `CHANGE_ME_LOCAL_DEV_ONLY` defaults are dev-only placeholders.

## Runbooks

### Payments stuck in PENDING
A surge of `PENDING` means the screening gate is holding payments — either genuine REVIEW outcomes or, more likely, **sanctions-service is unreachable** (fail-closed, ADR-0032 §C). Check sanctions-service health and the circuit-breaker state. Each held payment has an open AML case (`AML_HOLD` or `SCREENING_UNAVAILABLE`); resolve via aml-service / compliance ops. Payments are persisted, never lost.

### Event publish lag
Events publish directly and synchronously from `SctInstPaymentService` via `KafkaSctInstEventPublisher` — there is no outbox or dispatcher on this path (an earlier outbox pipeline was removed as dead code, issue #1034). If `openbank.sepa.instant.events` lags, check the Kafka emitter / broker health and producer-side logs on the publish call itself; a stuck payment transition (rather than a stuck backlog table) is the signal to look for.

### Flyway checksum mismatch on startup
Caused by a rewritten applied migration. Temporary fix: set `QUARKUS_FLYWAY_REPAIR_AT_START=true` in the GitOps env, then remove once the DB is settled. Never rewrite an applied migration.

### Execution timeout
Payments in `PROCESSING` past `execution_timeout_at` are surfaced by `findTimedOut` (partial index) and transitioned to `TIMEOUT` with a `SctInstPaymentTimeout` event.
