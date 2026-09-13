# Operations

## Build

```
./gradlew :openbank-lending-service:build
./gradlew detekt ktlintCheck koverVerify build   # local gate before a PR
```

- Convention plugin `openbank.quarkus-service` (ADR-0049 D1).
- Coverage floor: kover **40% LINE** (money-path baseline, ratchet-only; aspirational target 70%). REST/CDI/reflection classes are excluded from the metric.
- Image: **fast-jar only** (`-Dquarkus.package.jar.type=fast-jar`); the runtime stage COPYs `quarkus-app/`. Build host-side (`openbank-infra/scripts/build-push-service.sh openbank-lending-service`), never in-Docker Gradle.

## Configuration (key env vars)

| Var | Default | Purpose |
|---|---|---|
| `POSTGRES_PASSWORD` | `CHANGE_ME_LOCAL_DEV_ONLY` | DB credential. ⬜ No `BootstrapVerifier` exists, so nothing blocks this placeholder at startup (#8426) — in prod the value arrives through `secretKeyRef` from ESO/OpenBao in `lending-service.yaml` (ADR-0007) |
| `OIDC_CLIENT_SECRET` | `CHANGE_ME_LOCAL_DEV_ONLY` | Keycloak client secret |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8080/realms/openbank` | OIDC issuer |
| `LEDGER_SERVICE_URL` | `http://localhost:8101` | ledger-service REST client base |
| `LENDING_LEDGER_BACKEND` | `none` | `rest` activates `RestLedgerPostingAdapter` (build-time gated) |
| `LENDING_LEDGER_SYSTEM_ACTOR_ID` | `…00aa` | `createdBy` on ledger journals |
| `LENDING_GL_*` | (UUID defaults) | GL leaf accounts: loans-receivable, funding-clearing, interest-income, interest-receivable, loan-loss-expense, loan-loss-allowance |
| `LENDING_ACCRUAL_EVERY` | `24h` | Interest-accrual pass interval |
| `LENDING_ACCRUAL_BATCH_SIZE` | `500` | Installments per accrual pass |
| `LENDING_PROVISIONING_EVERY` | `720h` (~30d) | IFRS 9 provisioning cycle interval (ADR-0028 Phase 3); a plain duration, not calendar-month-aware |
| `LENDING_PROVISIONING_BATCH_SIZE` | `500` | ACTIVE loans scanned per provisioning cycle (no pagination beyond this — see threat model §5) |

`LENDING_LEDGER_BACKEND` is **build-time** (`@IfBuildProperty`): it selects the adapter at image build, not at runtime.

## Ports & probes

- **App:** `8126`. **Management:** `8086`, root-path `/q` (`quarkus.management.enabled=true`).
- **Health (SmallRye):** `/q/health`, `/q/health/live`, `/q/health/ready` on the management port.
- **Metrics:** Micrometer → Prometheus at `/q/metrics`. **Tracing:** OpenTelemetry OTLP → `http://localhost:4317` (`service.name=openbank-lending-service`).
- **Docs:** `/q/openbank/docs` (Docs-as-Service, ADR-0019). **Swagger UI:** `/api/docs`.
- Security headers set globally (HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy). Logs are JSON in non-dev.

## Serverless tier (ADR-0057)

Lending is a **money-path service**, and money-path services are **T0** by default (`rules.yaml: t0_baseline = money_path_services`) — always-on, no scale-to-zero. T0 membership is sacred: demotion would require an ADR-0030 threat model + 2 approvals. Note the in-process scheduled servicing loop (interest accrual) also argues against scale-to-zero.

## SLO (target)

_These are design-target SLOs for a production-shaped deployment — they are not measured, guaranteed, or met in the single-node sandbox._


| Metric | Target |
|---|---|
| Availability | 99.9% (T0, always-on) |
| Read latency (p99) | < 200 ms |
| Decision/disburse latency (p99) | < 500 ms (includes ledger posting hop when `backend=rest`) |
| Outbox dispatch lag | < 10 s (dispatcher ticks every 5 s) |
| RTO / RPO | 15 min / 5 min (see DORA mapping, [06 — Compliance](./06-compliance.md)) |

## Runbooks

### Outbox backlog growing
`lending_outbox.status` rows stuck unsent and `attempt_count` climbing ⇒ check Kafka connectivity and `last_error`. The dispatcher (`@Scheduled every 5s`, batch 25, `SKIP` overlap) retries automatically; a persistent backlog points at the broker or topic `openbank.lending.events`. Do not delete rows — they are the at-least-once delivery guarantee.

### Ledger posting failing
When `LENDING_LEDGER_BACKEND=rest`, postings go through `LedgerCallGuard` (fault tolerance) to `ledger-service POST /api/v1/journals`. Failures surface in disburse/repay/writeoff. Verify `LEDGER_SERVICE_URL`, the service OIDC token, and that GL `LENDING_GL_*` accounts exist in the chart. Postings are idempotent (reference = ledger idempotency key), so safe to retry.

### Interest accrual pass not running / lagging
Check the `InterestAccrualScheduler` logs ("interest accrual pass: N installments accrued"). Interval is `LENDING_ACCRUAL_EVERY` (default 24h, delayed 30s). The pass is idempotent (`interest_accrued` flag); a missed window self-heals on the next tick because it selects all due-but-unaccrued installments.

### IFRS 9 provisioning cycle not running / no delta posted
Check the `ProvisioningCycleScheduler` logs ("IFRS 9 provisioning cycle {period}: N loans assessed, M provisioning journals posted"). Interval is `LENDING_PROVISIONING_EVERY` (default ~720h/30d, delayed 60s). Zero journals posted for a period with loans assessed is **expected and correct** when no loan's stage/ECL changed since the prior period — check the `loan_provisioning` table for the period's rows before assuming a failure. The pass is idempotent per `(loan_id, period)`; a missed window self-heals on the next tick, but a book larger than `LENDING_PROVISIONING_BATCH_SIZE` is only partially covered per tick (no continuation cursor yet — tracked in the threat model).

### Flyway checksum mismatch on startup
Never rewrite an applied migration. Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` transiently, let the DB settle, then remove it.

## Deploy

GitOps (ArgoCD) per the platform pattern. For image-tag merge conflicts take `--ours` (freshly-built tag), `--theirs` for RBAC/config/env (CLAUDE.md). Version bumps and changelog are owned by release-please from Conventional Commits — never hand-edit `version.txt` or `CHANGELOG.md`.
