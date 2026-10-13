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
| `LENDING_PROVISIONING_EVERY` | `24h` | Daily IFRS 9 provisioning interval (ADR-0028 Phase 3) |
| `LENDING_PROVISIONING_BATCH_SIZE` | `500` | Eligible loans per query; the cycle continues until the date's unprovisioned population is exhausted |

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

### Evidence bundle and SENT-row retention (#11900)
`GET /api/v1/lending/applications/{id}/evidence` reads audit-service's chain (`GET /api/v1/audit/evidence/{id}`, `AUDIT_SERVICE_URL`) with the **caller's own token** — never the m2m client. 401/403 from audit-service pass through; anything else is **503**. There is deliberately no fallback to `lending_outbox`.

`lending_outbox` purges delivered rows like every other outbox (ADR-0329), but the switch is **off** here (`LENDING_OUTBOX_RETENTION_ENABLED`, default `false`) until this parity check passes once. audit-service subscribed to `openbank.lending.events` on 2026-07-31; events older than the topic's retention at that moment may never have reached the chain, and purging them from the outbox would lose them.

1. Exact match by event id (audit uses the producer's `eventId` as `entry_id` when the payload carries one). Export the lending ids, then look them up in audit:
   ```sql
   -- lending DB
   SELECT event_id FROM lending_outbox WHERE status = 'SENT';
   -- audit DB, with those ids loaded into a temp table `lending_ids(event_id uuid)`
   SELECT l.event_id FROM lending_ids l LEFT JOIN audit_entries a ON a.entry_id = l.event_id WHERE a.entry_id IS NULL;
   ```
2. Backstop for payloads without an `eventId` (audit then keys the entry by Kafka address): per-application counts must not be lower in audit.
   ```sql
   -- lending DB
   SELECT aggregate_id, count(*) FROM lending_outbox WHERE status = 'SENT' GROUP BY 1;
   -- audit DB
   SELECT aggregate_id, count(*) FROM audit_entries WHERE source_service LIKE '%lending%' GROUP BY 1;
   ```
3. Zero missing ids and no application with fewer audit rows ⇒ set `LENDING_OUTBOX_RETENTION_ENABLED=true`. Anything missing ⇒ do **not** enable; those loans' evidence exists only in the outbox and needs a replay into the chain first.

### Ledger posting failing
When `LENDING_LEDGER_BACKEND=rest`, postings go through `LedgerCallGuard` (fault tolerance) to `ledger-service POST /api/v1/journals`. Failures surface in disburse/repay/writeoff. Verify `LEDGER_SERVICE_URL`, the service OIDC token, and that GL `LENDING_GL_*` accounts exist in the chart. Postings are idempotent (reference = ledger idempotency key), so safe to retry.

### Interest accrual pass not running / lagging
Check the `InterestAccrualScheduler` logs ("interest accrual pass: N installments accrued"). Interval is `LENDING_ACCRUAL_EVERY` (default 24h, delayed 30s). The pass is idempotent (`interest_accrued` flag); a missed window self-heals on the next tick because it selects all due-but-unaccrued installments.

### IFRS 9 provisioning cycle not running / no delta posted
Check the `ProvisioningCycleScheduler` logs ("IFRS 9 provisioning cycle {period}: N loans assessed, M provisioning journals posted"). The default interval is 24h (delayed 60s), and `period` is the reporting date (`yyyy-MM-dd`). The pass scans nonterminal exposures missing that date's row in successive batches until a short batch proves exhaustion. Zero journals can be correct when ECL is unchanged; verify the `loan_provisioning` rows and `openbank_lending_provisioning_unprovisioned` gauge instead of inferring completeness from posting count. A failed pass or nonzero missing count is **not** a completed reporting date. A restart on the same date can resume idempotently. After the date changes, current loan, installment, collateral and risk state cannot be silently backdated to reconstruct an unfinished prior date; that requires controlled reconciliation or persisted historical inputs.

Each pass commits a `provisioning_cycle_run` row as `RUNNING` **before** its first loan posting, then records `COMPLETE` only after a zero-missing coverage read. A crash leaves `RUNNING`; a shortfall or unreadable coverage leaves `INCOMPLETE`. At the next run, calendar days skipped since the last recorded date are inserted as `MISSED`; the first recorded date is the deployment baseline. Inspect `SELECT period, status, started_at, checked_at, missing_loans FROM provisioning_cycle_run WHERE status <> 'COMPLETE' ORDER BY period` and the `openbank_lending_provisioning_unresolved_prior_days` gauge. A successful newer date neither clears these rows nor records workflow success while an older row is unresolved; the `LendingProvisioningPriorDayUnresolved` alert pages on that condition.

For an earlier-date gap, preserve the run row and compare its committed `loan_provisioning` rows and allowance outbox references with the period's independently retained loan and ledger evidence. Reconstruct neither missing rows nor journals from today's mutable loan, installment, collateral or risk state. Record the affected population, uncertainty, reviewer decision and the references of any approved **current-date** accounting adjustment in the reconciliation case. The run row remains unresolved until a reviewed reconciliation closure mechanism is implemented; do not relabel it `COMPLETE` merely to silence the alert.

### Ledger backfill (one-off, #10746)
For loans whose GL history never reached the ledger (#6057). ROLE_FINANCE or ROLE_ADMIN (humans only), two different people (#10618). The admin console runs the whole flow under Balance sheet & risk → Ledger backfill.
1. **Dry-run** (writes nothing): `GET /api/v1/lending/ledger-backfill/plan?cutoverDate=<today>&disbursedBefore=<date>`. Check `executable=true`, `journalCount`, `plan.tieOut[*].ties=true` (Loans Receivable after == lending unpaid principal per currency) and `glTotals`. Confirm in ledger that none of the `loan:<id>:…` references exist yet.
2. **Propose** (maker): `POST /api/v1/lending/ledger-backfill/requests` `{"cutoverDate":"<today>","disbursedBefore":"<date>"}`.
3. **Approve** (checker, a different finance user or admin): `POST /requests/{id}/decide` `{"approve":true,"reason":"…"}`. Self-approval answers 422.
4. **Execute**: `POST /requests/{id}/execute?execute=true` on the cut-over day. Without `execute=true` it only returns the plan. 409 means the book moved since approval, the cut-over date passed, or another run holds the lease: propose again.
5. A loan whose leg fails stops at that leg, the others continue, and the request stays APPROVED. Fix the cause and run step 4 again. Legs already booked replay as no-ops in ledger.
6. **Reverse**: reverse each journal in ledger-service (`reverseJournal`), finding them by idempotency key `loan:<id>:…`. The reversal lands in the current open day.
Journals are booked on the cut-over date, with the original event date as `valueDate`. GL only: nothing is ever credited to a customer account.

### Flyway checksum mismatch on startup
Never rewrite an applied migration. Set `QUARKUS_FLYWAY_REPAIR_AT_START=true` transiently, let the DB settle, then remove it.

## Deploy

GitOps (ArgoCD) per the platform pattern. For image-tag merge conflicts take `--ours` (freshly-built tag), `--theirs` for RBAC/config/env (CLAUDE.md). Version bumps and changelog are owned by release-please from Conventional Commits — never hand-edit `version.txt` or `CHANGELOG.md`.
