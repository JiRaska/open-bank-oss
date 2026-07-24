# Threat Model — openbank-settlement-service (ADR-0101 Temporal P3)

Date: 2026-06-19
Status: Current
Author(s): OpenBank platform
Reviewed by: (pending 2nd approval — money-path rule)

---

## Service overview

`openbank-settlement-service` orchestrates the three-step money movement for interbank and
intraday settlement: **debit payer → credit payee → book to ledger**. It is the root cause
origin of the settlement money-bug (found 2026-06-19) and the primary motivation for ADR-0101
Temporal migration: the hand-rolled saga had no explicit intermediate-state timeout, allowing
the saga to hang indefinitely without compensation.

This document covers the Temporal-based replacement workflow and its security posture.

---

## Assets

| Asset | Sensitivity | Owner |
|-------|-------------|-------|
| Settlement DB (CNPG `settlement-db`) | HIGH — holds debit/credit state | platform |
| Temporal workflow history (namespace `openbank-settlement`) | HIGH — full execution trace | platform |
| Payer account balance (via `balance-service` port) | CRITICAL | balance-service |
| Payee account balance | CRITICAL | balance-service |
| Ledger entries (via `ledger-service` port) | CRITICAL | ledger-service |

---

## Trust boundary

```
[caller] ──HTTPS (OIDC)──▶ POST /api/v1/settlements ─▶ [settlement-service] ─▶ [settlements DB]
                                          │ originate → settle
                                          ▼
[Temporal server] ──gRPC (mTLS)──▶ [settlement-service worker]
                                          │
                                ┌─────────┼──────────┐
                                ▼         ▼          ▼
                          debit-port  credit-port  ledger-port
                          (balance)   (balance)   (ledger)
```

The settlement-service worker receives workflow tasks from Temporal over gRPC. The worker calls
three downstream ports. Each port call is an activity — idempotency-keyed so Temporal can replay
without double-execution.

**Origination boundary (added with DB persistence + REST origination).** `POST /api/v1/settlements`
creates a PENDING settlement and starts its workflow. It is a money-movement trigger, so it is
gated by `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` (coarse) **and** the fine-grained
`settlement.create` OPA action (advisory until the settlement OPA policy lands — see Residual
risks). Settlements are now persisted in the `settlements` table (DB-backed Panache repository,
replacing the former in-memory stub), so settlement state is durable across restarts and auditable.

---

## Threat enumeration (STRIDE)

### S — Spoofing

| ID | Threat | Mitigation |
|----|--------|------------|
| S1 | Attacker impersonates Temporal server, injects malicious workflow tasks | mTLS between Temporal server and workers (both directions); OPA activity interceptor (`OpaActivityInterceptor`) rejects tasks not matching policy |
| S2 | Attacker impersonates settlement-service to call balance/ledger | Service mesh mTLS (SPIFFE identity); OPA authz on REST receivers |

### T — Tampering

| ID | Threat | Mitigation |
|----|--------|------------|
| T1 | Replayed debit activity credits twice | `referenceId = workflowRunId + activityId` stored before side-effect; idempotency guard in balance-service |
| T2 | Settlement DB record modified out-of-band (bypassing workflow) | Postgres row-level security; audit trail in `openbank-libs/audit` |
| T3 | Temporal workflow history modified | Temporal's history is append-only; CNPG backups every 30 days |

### R — Repudiation

| ID | Threat | Mitigation |
|----|--------|------------|
| R1 | Settlement denied by payer ("I never authorised this") | Temporal workflow execution ID links to the initiating payment ID in `sepa-payment-service` / `domestic-payment-service`; full chain queryable from admin UI |
| R2 | Compensation claimed to have run when it did not | Temporal activity history records every attempt; compensation activities update status to `REVERSED`/`CREDITED_REVERSED`/`LEDGER_REVERSED` atomically |

### I — Information disclosure

| ID | Threat | Mitigation |
|----|--------|------------|
| I1 | Temporal workflow history contains PII (account IDs, amounts) | Namespace `openbank-settlement` restricted to internal OIDC audience (ADR-0056); GDPR retention policy 90 days |
| I2 | Logs leak account numbers | Loggers use settlementId (UUID), not account numbers |

### D — Denial of service

| ID | Threat | Mitigation |
|----|--------|------------|
| D1 | Temporal server unavailable, blocks all settlement | Feature flag `openbank.temporal.enabled` — legacy path coexists; flip to false for emergency fallback |
| D2 | Activity storm (mass settlement retry) | `scheduleToCloseTimeout=2h`, `maxAttempts=5`, exponential backoff — bounded blast radius |

### E — Elevation of privilege

| ID | Threat | Mitigation |
|----|--------|------------|
| E1 | Attacker injects a workflow that calls `reverseBookToLedger` on a legitimate settlement | OPA policy gate: only activities matching `data.openbank.settlement.activity.allow` are dispatched; `reverseBookToLedger` requires `compensation=true` context |
| E2 | Service account token used to submit arbitrary workflows | Temporal namespace ACL restricts task queue submission to settlement-service service account (SPIFFE `spiffe://openbank/ns/openbank-settlement/sa/settlement-service`) |

---

## Residual risks

0. **Persistence is now DB-backed (was an in-memory stub).** `SettlementRepositoryImpl` previously
   stored settlements in a `ConcurrentHashMap`; it is now a hibernate-reactive Panache repository on
   the `settlements` table. Settlement state survives restarts and is auditable. The real
   balance/ledger adapters landed earlier (ADR-0101 #1522), so a settlement originated via
   `POST /api/v1/settlements` with Temporal enabled does move real money end-to-end. **The
   origination endpoint is the new highest-value attack surface** — keep its role gate + OPA action
   tight, and never expose it without authentication.

1. ~~**OPA policy for `settlement.create` (origination) not yet written.**~~ **Closed** (ADR-0034
   Phase 5, issue #266). `settlement_rest_ext.rego` now grants `operator-settlement-write` for
   HUMAN ROLE_OPERATOR/ROLE_ADMIN on `settlement.create`; there is deliberately NO SERVICE/M2M allow
   rule — a fleet audit found no in-repo caller ever invokes `POST /api/v1/settlements` as a
   service-to-service action (no rest-client config anywhere in the repo targets it, and the
   NetworkPolicy ingress-allow-list only admits admin-ui, which forwards the operator's own bearer
   token). `AUTHZ_ENFORCE=true` is now set on the settlement Rollout; the OPA sidecar is deployed.
   `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` remains the coarse outer gate (unchanged, pre-existing),
   so a SERVICE-token caller would still pass RBAC but is denied by OPA (deny-by-default, no
   matching `allowed_reasons`) — if a genuine M2M caller for this action is ever verified, add a
   narrow `service-settlement-m2m` rule the way `service-domestic-payment-m2m` / `service-sca-m2m`
   do it, not a blanket SERVICE allow.

2. ~~**OPA policy for settlement activities not yet written.**~~ **Closed** —
   `openbank-settlement-service/src/main/resources/opa/settlement_activity.rego` exists and is bundled
   (`settlement-opa-bundle.yaml`), so `OpaActivityInterceptor` has a real policy to evaluate rather
   than fail-closing on a missing one. This was the pre-condition for the flag flip below.

3. **Temporal is now the SOLE orchestrator (issue #1917, ADR-0120 Phase 6).** The
   `openbank.temporal.enabled` dispatch gate is removed and the in-process legacy saga
   (`legacySettle`) is deleted. This closes a compensation gap: the legacy path flipped a mid-flight
   failure straight to `REJECTED` **without reversing an already-moved debit/credit**, whereas
   `SettlementWorkflow` runs `reverseBookToLedger → reverseCredit → reverseDebit` before rejecting.
   Flip pre-conditions are verified: Temporal server healthy, `openbank-settlement` namespace
   registered, `settlement_activity.rego` present (risk 2). Worker registration is gated on
   `openbank.settlement.worker.enabled` (default true; false in `%test`). **Cutover risk:** with no
   fallback path, a settlement cannot be processed if the Temporal worker is not registered or the
   frontend is unreachable — a sandbox canary must confirm the worker registers and a real settlement
   drives `PENDING → BOOKED` before merge, and the deterministic simulation (ADR-0100/0115) stays green.

4. **2-approval gate** — money-path rule requires 2 approvals. This PR has 1 (automated review).
   Second approval from a human committer required before merge per rules.yaml.

---

## DORA / PSD2 / PCI alignment

- **DORA Art. 11** — Temporal workflow history = reconstructable execution trace for every settlement.
- **DORA Art. 17** — DST (ADR-0100) can replay the workflow deterministically for ICT testing evidence.
- **PSD2 Art. 5(3)** — payment authentication context preserved across any Temporal restart.
- **PCI DSS Req. 10** — activity history + `openbank-libs/audit` hash chain = tamper-evident audit log.

---

## References

- ADR-0030 (threat model policy)
- ADR-0034 (OPA unified authz — `OpaActivityInterceptor`)
- ADR-0101 (Temporal durable execution — this migration)
- Settlement money-bug (2026-06-19, root cause: missing intermediate-state timeout in hand-rolled saga)
