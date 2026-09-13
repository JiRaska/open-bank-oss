<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Runbook 0006 — Temporal settlement go-live (ADR-0101 P3)

**Status:** prepared, NOT executed. Every step below is a deliberate operational flip to run at
the console with canary + monitoring — do not automate.

**Scope:** `openbank-settlement-service` is the only money-path service whose Temporal durable
workflow is still **off**. `sepa-payment`, `domestic-payment`, `fx-service` and
`statement-service` already run on Temporal (flags on in GitOps; see ADR-0101 notes). This
runbook flips settlement, in the safe order, with a stop/rollback at each gate.

## Current state (verified on `main`, 2026-06-23)

| Item | Where | Value now | Target |
|------|-------|-----------|--------|
| Temporal flag | `gitops/components/payments/payments-services.yaml` (settlement-service env) | `OPENBANK_TEMPORAL_ENABLED` **absent** ⇒ `openbank.temporal.enabled=false` | `true` |
| Temporal server URL | same | `temporal-frontend.temporal.svc.cluster.local:7233` (set) | unchanged |
| Temporal namespace | `openbank-settlement` (settlement's own `TemporalConfig` `@WithDefault`, like fx/statements — NOT `openbank-default`) | was **missing** from the registration `NAMESPACES` list → go-live NOT_FOUND | **added** ✅ |
| GL debit account | `SETTLEMENT_GL_DEBIT_ACCOUNT_ID` | `a0000000-…-0002` (2100 CZK deposit-control) | unchanged ✅ (correct for CZK) |
| GL credit account | `SETTLEMENT_GL_CREDIT_ACCOUNT_ID` | `a0000000-…-0002` (same — payer/payee on subAccountId) | unchanged ✅ (correct for CZK) |
| OPA enforcement | no `AUTHZ_ENFORCE` set ⇒ libs default (advisory) | **Corrected 2026-08-20 (#6055):** `opa/settlement_activity.rego` was never deployed — it was in no bundle ConfigMap and is now deleted. The policy that IS deployed is `settlement_rest_ext.rego` (REST action `settlement.create`), in `settlement-opa-bundle.yaml`. | `true` after validation |

**GL accounts — CORRECTION (verified 2026-06-23):** an earlier draft of this runbook called the
identical debit/credit GL UUID a blocker. It is **not** — it is correct. `LedgerBookAdapter` sets
`subAccountId` to the payer on the debit leg and the payee on the credit leg, so both legs post to
the **same** customer deposit-control account (`2100` = `a0000000-…-0002`, CZK) and the per-customer
movement rides on the deposit-control **sub-ledger** (ADR-0039 / migration V7). That is a real
per-customer transfer (payer −amount, payee +amount), not a no-op; the ledger's
`loadAndValidateGlAccounts` accepts it (it never requires distinct accounts, only matching currency
and `subAccountId`-on-deposit-control-only). **Residual, not a blocker:** the config is CZK-only —
a non-CZK settlement would fail GL validation (currency mismatch) and would need the matching
per-currency control account (`2101` EUR / `2102` USD / `2103` GBP). Track that if/when settlement
handles other currencies.

## Order of operations (each is a gate)

### Gate 0 — GL accounts (verified correct; multi-currency is the only follow-up)

No change required for CZK settlement (see the correction above): debit and credit both = `2100`
(`a0000000-…-0002`) with payer/payee on `subAccountId` is the intended deposit-control sub-ledger
posting. Only revisit if settlement must book a non-CZK currency, which needs the matching
per-currency control account.

### Gate 1 — Enable Temporal for settlement (canary)

1. Patch GitOps (settlement-service env):
   ```yaml
   - name: OPENBANK_TEMPORAL_ENABLED
     value: "true"
   ```
   (SERVER_URL already present; NetworkPolicy `temporal-platform-ingress` already admits the
   payments namespace — PR #1600.) **The `openbank-settlement` namespace MUST be registered** in
   `temporal-namespace-config.yaml` `NAMESPACES` first — the first go-live (PR #1829) missed
   this and the worker logged `NOT_FOUND: Namespace openbank-settlement is not found`; fixed by
   adding it to the registration. Verify the PostSync hook created it before relying on workflows.
2. Roll ONE settlement replica first (canary) if running >1; otherwise watch the single pod's
   first reconcile closely.
3. **Pass criteria (must all hold ~10 min):**
   - pod log shows `Temporal worker started` and **0** `NOT_FOUND: Namespace … is not found`;
   - a test settlement batch completes a workflow end-to-end (Temporal UI / `temporal workflow
     list -n openbank-settlement`);
   - ledger shows a balanced settlement journal (debit/credit on `2100` with payer/payee subAccountId);
   - no saga/compensation error spikes; `temporal_workflow_failed_total` flat.
4. **Rollback:** set `OPENBANK_TEMPORAL_ENABLED=false`, sync — settlement falls back to the
   in-process path immediately (no data migration; the flag is a clean cutover).

### Gate 2 — OPA enforcement (after Gate 1 is stable for ≥1 settlement cycle)

1. Confirm the deployed policy allows legitimate traffic in advisory logs (no would-be denials).
   **Corrected 2026-08-20 (#6055):** this step used to say "confirm `settlement_activity.rego` is
   returning `allow` for the 7 activity methods" — that was unperformable, because nothing ever loaded
   or queried that file and there is no activity-level authorization on the worker at all (threat model
   residual risk 2, re-opened). The observable gate is the REST one: `settlement.create` on
   `POST /api/v1/settlements`, evaluated against `data.openbank.rest.allow`. Temporal activities inside
   the worker are not evaluated by OPA and flipping this flag does not change how they are dispatched.
2. Patch GitOps (settlement-service env):
   ```yaml
   - name: AUTHZ_ENFORCE
     value: "true"
   ```
3. **Pass criteria:** no spurious `PDP denied` on legitimate settlement activities; OPA sidecar
   healthy; latency unaffected.
4. **Rollback:** `AUTHZ_ENFORCE=false`, sync — back to advisory instantly.

## Monitoring (watch throughout)

- Temporal: `temporal_workflow_completed_total` / `_failed_total` / `_timeout`, schedule-to-start
  latency for task-queue `openbank-settlement`; Temporal UI namespace `openbank-settlement`.
- Settlement: pod readiness (note: readiness is TCP/HTTP, **not** Temporal — a NOT_FOUND poller
  loop still reads Healthy, so check logs explicitly), saga compensation rate, error log rate.
- Ledger/balance: settlement journals balance (`Σ debit == Σ credit`), no unexpected GL drift.
- Grafana: payments dashboard; alert on `PaymentServiceDown` / `HighErrorRate` for settlement.

## Go / no-go

GO only when: settlement is CZK (GL config is CZK-only); the canary worker starts with **0**
`NOT_FOUND` namespace errors; rollback rehearsed mentally (one-line flag revert). NO-GO if the
namespace poller errors, a settlement journal fails to balance, or a non-CZK settlement is in
flight (would fail GL currency validation until per-currency control accounts are configured).

## References

- ADR-0101 (Temporal durable execution) — settlement is P3 (#1471), real adapters + OPA (#1522).
- New-Temporal-service checklist + namespace-registration mechanics: see the ADR-0101 notes and
  `gitops/components/temporal/temporal-namespace-config.yaml` (the list and the script; the
  PostSync hook and the daily reconcile CronJob both read it).
- DST harness (ADR-0100, `openbank-simulation`) can model the settlement saga compensation under
  fault injection before/after this flip.
