# Threat model — `openbank-lending-service`

> Scope: the lending/credit bounded context (origination, servicing, collateral, IFRS 9 provisioning)
> introduced in [ADR-0028](../adr/0028-lending-bounded-context.md). Money-path service per
> `openbank-libs/governance/rules.yaml: money_path_services`; this document is the ADR-0030 D2
> mandatory threat model. It records the trust boundaries, the controls already in place, and the
> maturity gaps tracked as roadmap — it deliberately does not enumerate exploitable specifics.

## 1. Assets

| Asset | Why it matters |
|---|---|
| Loan book (application, loan, schedule, stage) | Customer financial data (GDPR); the source of AnaCredit / FINREP / IFRS 9 returns. |
| Cash events posted to the ledger | Real money movement (disbursement, repayment split, write-off). Integrity here is the money-path invariant. |
| Credit decisions (maker / checker) | Governed origination (EBA/GL/2020/06); a forged or single-actor approval is a control failure. |
| Collateral register & valuations | Drives LGD / haircut and capital; tampering (or a fabricated declared value / haircut) understates risk and, since Phase 3 increment 2, is now **wired into** the ECL calc via `Ifrs9.collateralAdjustedLgd` — a bad registration directly and immediately reduces the reported ECL, not just an AnaCredit field. Registration is gated the same way every other lending write is: `@RolesAllowed` + `@Authorize`; **it is not yet under the four-eyes/maker-checker control that origination and disbursement have** (see §5). |
| Risk parameters (PD / LGD / bureau data) | Inputs to ECL; manipulation distorts impairment and capital. **Currently flat, conservative placeholder constants (`ConservativeRiskParameterSource`), not a calibrated risk model — see 06 — Compliance for the explicit non-production caveat.** The collateral-adjusted effective LGD (Phase 3 increment 2) is *derived* from these same constants plus the collateral register — a bad collateral entry does not invent a new attack surface on PD/LGD itself, but it does change what "distorts impairment" means: `haircutAdjustedCollateralValue` is now a second untrusted-input path into the LGD term, not just `lgd` itself. |
| IFRS 9 provisioning history (`loan_provisioning`) | Per-loan-per-period stage/ECL record; the delta baseline for the next cycle's ledger posting and (per ADR-0028) a future AnaCredit/FINREP input. Corruption or a skipped row causes silent under/over-provisioning. |

## 2. Trust boundaries

1. **Client → service (REST).** Authenticated callers (officers, risk, compliance, admin) over the
   gateway. All endpoints are role-gated (D5); no `@PermitAll`.
2. **Service → ledger-service (synchronous REST).** The loan book never owns balances; it posts
   double-entry journals to `ledger-service` (`POST /api/v1/journals`, `ROLE_OPERATOR`) authenticated by
   client-credentials, behind a `LedgerCallGuard` resilience boundary. This is the money-path crossing.
3. **Service → event stream (transactional outbox).** Loan state changes flow to the analytics/downstream
   plane through the outbox (ADR-0003) — one local transaction, single extraction path, no second read
   path into the lending DB.
4. **Service → credit bureau / risk-parameter / valuation feeds (ports).** `@Default` no-op today; real
   integrations land later as build-time-gated `@Alternative` adapters — each a future trust boundary.
5. **Service → its own Postgres schema.** Intra-service tables only; no cross-schema reads.

## 3. Controls in place (this slice)

- **Authn/z.** Every endpoint `@RolesAllowed` with the appropriate role; sensitive reads (loan,
  schedule, arrears, impairment) gated to lending/risk/compliance/audit and audit-logged.
- **Server-side acting principal.** The acting principal for every state-changing endpoint
  (apply, decide, disburse, write-off) is the authenticated JWT subject via `actor()` — **never** a
  client-supplied field. (Write-off was hardened to this in PR #158.)
- **Four-eyes (maker-checker).** A credit decision must be made by a principal *different* from the
  application maker; disbursement by a principal different from the approver. Enforced server-side in the
  application state machine, not a UI nicety.
- **Money-path integrity.** Postings are double-entry via the pure, unit-tested `LendingJournalFactory`
  (balanced legs asserted per `PostingKind`); the loan book mutates no balances itself.
- **Idempotent posting.** A deterministic `idempotencyKey` / `transactionId` (`UUID.nameUUIDFromBytes`)
  makes at-least-once delivery safe — a replay posts the same journal id and the ledger dedupes.
- **State-transition guards.** Write-off refuses a non-ACTIVE or fully-repaid loan; disbursement requires
  an APPROVED application. Illegal transitions return 409, not a silent mutation.
- **Offline-buildable defaults.** No-op `@Default` ports mean the service boots with zero external
  dependency; real integrations are explicit build-time opt-ins.
- **Secrets.** No hardcoded credentials; config values are env-var placeholders.
- **Provisioning cycle integrity (ADR-0028 Phase 3, new this slice).** The scheduled IFRS 9 provisioning
  pass (`ProvisioningCycleScheduler` → `LendingService.runProvisioningCycle`) is a system-actor process,
  not user-triggered — there is no REST endpoint that lets a caller invoke it or supply its inputs. Its
  three specific risks and the mitigation each gets:
  - **Under-provisioning via wrong stage bucketing.** Stage 2/3 boundaries (30/90 DPD) are the pure,
    unit-tested `Ifrs9.stage`/`Delinquency.isDefaulted` primitives — the same math `GET
    .../provisioning` already exposes for inspection — not re-derived ad hoc in the scheduler. Boundary
    cases (exactly 30, exactly 90 DPD) are explicitly unit-tested.
  - **Double-provisioning via a missed delta calculation.** The cycle always posts `newEcl − priorEcl`
    (`postProvisioningDelta`), never the full ECL, and `(loanId, period)` is `UNIQUE` in `loan_provisioning`
    — a re-run for an already-provisioned period is a verified no-op (`findByLoanAndPeriod` short-circuits
    before any read of risk parameters or any ledger call), not merely "unlikely to happen twice."
  - **Wrong-direction journal.** `LendingJournalFactory.buildProvisioningLines` is unit-tested for both
    signs explicitly (increase: DEBIT expense / CREDIT allowance; decrease: reversed) and asserts the
    loan principal GL (Loans Receivable) is never touched by a provisioning entry.
  - **Roadmap gap, not yet mitigated:** the batch scan (`LoanRepository.findActive(limit)`) is a single
    page with no continuation cursor — a book larger than `limit` silently leaves the tail unprovisioned
    for that cycle with no alert. Acceptable for a first increment on a small loan book; needs a
    pagination/completeness check before the book grows past one batch.
- **Collateral-adjusted LGD correctness (ADR-0028 Phase 3 increment 2, new this slice).** The pure
  `Ifrs9.collateralAdjustedLgd` function and its call site in `LendingService.applyCollateral` carry
  their own specific risks and mitigations:
  - **Negative LGD from over-collateralization.** The function is unit-tested to floor the result at
    zero when haircut-adjusted collateral cover exceeds EAD — a negative LGD has no economic meaning and
    would invert the ECL sign; `collateralAdjustedLgd` clamps to `[0, lgd]` (`coerceIn`), never returning
    a value outside that range regardless of input magnitude.
  - **Regression for the existing (uncollateralized) loan book.** `applyCollateral` short-circuits on
    `registered.isEmpty()` and returns the risk-parameter source's `EclInputs` unchanged — a loan with no
    collateral registered is byte-identical to pre-increment behavior; covered explicitly by a
    no-regression unit test, not merely an absence of a new test failure.
  - **A currency mismatch silently corrupting the sum.** `applyCollateral` filters registered collateral
    to items whose `marketValue.currency` matches the loan's exposure currency before summing — a
    mis-registered collateral item in a different currency is excluded from the cover calculation rather
    than throwing (the loan book is single-currency per ADR-0028 D3, so this is a defensive filter against
    a data-entry error, not an expected path) or, worse, silently mixing currency amounts in the sum.
  - **Roadmap gap, not yet mitigated — collateral registration has no four-eyes control.** Unlike credit
    decisioning (`lending.approve`) and disbursement (`lending.disburse`), `POST .../collateral` is a
    single-actor write: any principal holding `lending.create` on the loan can register collateral whose
    declared value/haircut now directly reduces the reported ECL. A single compromised or careless
    officer account can therefore understate provisioning without a second reviewer. Tracked as a roadmap
    item (see §5) — the same shared `ApprovalStore`/`AuthorizeInterceptor` mechanism used for disbursement
    (§7) is the natural extension point.
  - **Roadmap gap, not yet mitigated — no staleness bound on collateral valuation.** `marketValue` is
    whatever was declared (or externally revalued, if `CollateralValuationPort`'s no-op default is ever
    replaced) at registration time; there is no re-validation, expiry, or staleness flag, so an LGD
    reduction can be based on an arbitrarily old valuation. See the ADR-0028 delivery note's explicit
    "no real-time revaluation" caveat.

## 4. STRIDE summary

| Category | Posture | Notes / roadmap |
|---|---|---|
| **Spoofing** | Mitigated | JWT-subject identity end-to-end; client-credentials to ledger. |
| **Tampering** | Mitigated (in-flight), maturing (at-rest) | Double-entry + idempotency on the money-path; DB-level integrity & immutable audit of impairment movements tracked as roadmap. |
| **Repudiation** | Mitigated | Maker/checker identities and sensitive reads audit-logged; write-off attribution now server-derived. |
| **Information disclosure** | Mitigated | Role-gated GDPR-class reads; analytics only via the outbox stream. |
| **Denial of service** | Partially mitigated | `LedgerCallGuard` (`@Retry`/`@Timeout`/`@CircuitBreaker`) bounds ledger calls; per-tenant rate limiting is a gateway-layer roadmap item. |
| **Elevation of privilege** | Mitigated | No `@PermitAll`; least-privilege roles per endpoint; four-eyes prevents single-actor origination. |

## 5. Maturity / roadmap (tracked, not yet built)

- **Phase 2 origination cycle** — the explicit maker→checker→disburse REST flow as a first-class state
  machine (ADR-0028 D6). Until then four-eyes is enforced at decision/disburse but the full UI cycle is
  scaffolded.
- **Risk-parameter provenance** — when the no-op PD/LGD/bureau ports are replaced by real adapters, each
  becomes a trust boundary needing its own authn, integrity and audit treatment. Until then, the
  provisioning cycle's ECL is only as good as `ConservativeRiskParameterSource`'s flat constants — a
  **model-risk gap**, not a security control gap, but load-bearing enough to call out here: do not treat
  the provisioning cycle's output as an examiner-ready capital number.
- **Provisioning batch completeness** — `LoanRepository.findActive(limit)` has no pagination/continuation;
  a book larger than one batch silently under-provisions the tail with no alert (see §3).
- **Impairment-movement immutability** — append-only / tamper-evident storage for IFRS 9 stage and ECL
  movements feeding FINREP F 12, to strengthen the tampering/repudiation posture at rest. `loan_provisioning`
  is insert-only from the application code today, but nothing at the DB level prevents an UPDATE/DELETE.
- **Money-path mutation testing** — pitest on the journal/amortization domain math (rules.yaml
  `money_path_depth`, currently `planned`) — should extend to `LendingJournalFactory.buildProvisioningLines`
  and the delta calculation once adopted.
- **Collateral registration has no four-eyes control (ADR-0028 Phase 3 increment 2, new this slice).**
  `POST /api/v1/lending/loans/{id}/collateral` is gated by role (`@RolesAllowed`/`@Authorize`) like every
  other lending write, but — unlike credit decisioning and disbursement — a single principal can both
  register collateral and have it immediately reduce the reported ECL on the next provisioning pass. Now
  that collateral is wired into LGD (rather than being inert AnaCredit metadata), this is a genuine
  provisioning-integrity gap, not just a data-quality one: it should get the same maker-checker treatment
  origination/disbursement have (§7's `ApprovalStore`/`AuthorizeInterceptor` mechanism is the natural
  extension point) before this is relied on for anything beyond a first-pass estimate.
- **No collateral valuation staleness bound.** `marketValue`/`haircut` are read as-declared at
  registration time with no expiry, re-validation, or staleness flag on the LGD reduction — see §3 and
  the ADR-0028 delivery note.
- **Haircut calibration is a placeholder, not a risk model.** Per-`CollateralType` haircuts are supplied
  by the caller at registration time (`CollateralRequest.haircut`) with no platform-enforced or
  actuarially-derived table — see the ADR-0028 delivery note's explicit non-calibration caveat.

## 6. Out of scope

Infrastructure-layer controls (network egress, secret storage, runtime isolation) are covered by the
platform substrate (ADR-0027) and the unified authz layer (ADR-0034), not duplicated here.

## 7. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /applications/{id}/disburse` (`lending.disburse`) is the money-path action being wired into
the shared, opt-in four-eyes mechanism (rollout tracked in issue #413; sepa-payment's `PATCH
/status` was the pilot). Disbursement books the loan and its schedule and is the point at which
funds actually move (`LedgerCallGuard` → `ledger-service`), so it is framed here as an **outbound
money-movement action**, same risk class as the SEPA pilot's status transition. New endpoint
`PATCH /api/v1/lending/approvals/{id}` lets a DIFFERENT lending officer decide the resulting
`PendingApproval`; the maker retries `POST /applications/{id}/disburse` with an `X-Approval-Id`
header. **`authz.four-eyes.enforce` stays `false` in this PR** — the `ApprovalStore`/endpoint are
wired, but blocking is a deliberate follow-up flip, not bundled here (see ADR-0155).

Note: lending already has an *application-level* maker-checker control on the origination
decision (`lending.approve`, §3 above — decide must differ from the application's maker). The
ADR-0155 mechanism here is a second, independent control specifically on **disbursement**: even
after a valid checker approved origination, releasing the funds is itself now gate-able by a
second approver via the shared `ApprovalStore`/`AuthorizeInterceptor` path (currently advisory,
`enforce=false`).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than a lending officer decides an approval | `@RolesAllowed("ROLE_LENDING_OFFICER","ROLE_ADMIN")` + OPA `@Authorize(action="lending.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own disbursement request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different disbursement | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated disbursement | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see Residual risks below: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals loan/action metadata to an unauthorized caller | `decide` requires the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /applications/{id}/disburse` to exhaust Redis with pending approvals | Bounded by the same rate-limit controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Lending officer (checker) → PATCH /api/v1/lending/approvals/{id} → Redis
(approval:*)` alongside the existing `POST /applications/{id}/disburse` edge; the maker's retry
reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties, disbursement) + confidentiality (approval
record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do not
change any existing request's outcome until explicitly flipped.

**Residual risk:** four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent
audit trail (ADR-0155) — a durable-audit requirement for "who approved what, forever" would need
an additional store; not implemented in this PR.

## 8. Change log

- **2026-07-08** — ADR-0155 four-eyes enforcement rollout (issue #413), mirroring the
  sepa-payment pilot. New endpoint `PATCH /api/v1/lending/approvals/{id}` + `ApprovalConfig`
  (Redis-backed `ApprovalStore`) + `AuthorizeInterceptor` four-eyes gate on `lending.disburse`
  (openbank-libs-runtime, shared, opt-in). New STRIDE supplement §7. `authz.four-eyes.enforce`
  defaults `false` — no behavior change to any existing request in this PR; flipping it is a
  tracked follow-up. No DB schema change (Redis, TTL-bounded); rollback = revert the commit (or
  leave `authz.four-eyes.enforce=false`, its default).
- **2026-07-08** — ADR-0028 Phase 3 increment 2 (issue #604): collateral-adjusted LGD. The pure
  `Ifrs9.collateralAdjustedLgd` (openbank-libs-domain) + `LendingService.applyCollateral` wire the
  already-registered collateral into the ECL calculation for the first time — previously it was
  recorded but never consulted. Updated §1 (Collateral register & valuations, Risk parameters
  assets), §3 (new control block: negative-LGD floor, no-regression guarantee, currency-mismatch
  filter), §5 (new roadmap gaps: collateral registration has no four-eyes control, no valuation
  staleness bound, haircut calibration is a placeholder). No DB schema change (reads the existing
  `collateral` table); no new endpoint; rollback = revert the commit (LGD reverts to the flat
  placeholder for every loan, identical to before this increment).
