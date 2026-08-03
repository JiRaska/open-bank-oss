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
| Collateral register & valuations | Drives LGD / haircut and capital; tampering (or a fabricated declared value / haircut) understates risk and, since Phase 3 increment 2, is **wired into** the ECL calc via `Ifrs9.collateralAdjustedLgd` — a bad registration would directly and immediately reduce the reported ECL. **Closed (issue #621):** registration is now four-eyes / maker-checker, same shape as origination — a registered item is `PENDING` and excluded from the LGD adjustment until a DIFFERENT principal approves it (see §7). |
| Risk parameters (PD / LGD / bureau data) | Inputs to ECL; manipulation distorts impairment and capital. **Currently flat, conservative placeholder constants (`ConservativeRiskParameterSource`), not a calibrated risk model — see 06 — Compliance for the explicit non-production caveat.** The collateral-adjusted effective LGD (Phase 3 increment 2) is *derived* from these same constants plus the collateral register — a bad collateral entry does not invent a new attack surface on PD/LGD itself, but it does change what "distorts impairment" means: `haircutAdjustedCollateralValue` is now a second untrusted-input path into the LGD term, not just `lgd` itself. |
| IFRS 9 provisioning history (`loan_provisioning`) | Per-loan-per-period stage/ECL record; the delta baseline for the next cycle's ledger posting and (per ADR-0028) a future AnaCredit/FINREP input. Corruption or a skipped row causes silent under/over-provisioning. |
| Restructuring authority (reschedule/forgiveness, issue #667/#668) | `RescheduleLoanUseCase` can discard a loan's remaining unpaid schedule and grant partial debt forgiveness — a genuine credit-loss event. Single-actor (`ROLE_CREDIT_RISK`/`ROLE_COMPLIANCE`/`ROLE_ADMIN`, JWT-subject captured server-side), same authority shape as `WriteOffLoanUseCase`; a compromised or over-privileged credit-risk identity could forgive principal without a second approver. Bounded by role-gating and the existing money-path audit trail (`loan.rescheduled` outbox event); no domain-level four-eyes check today (see §5). |

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
6. **Service → in-namespace Redis (four-eyes `ApprovalStore`).** The ADR-0155 `RedisApprovalStore`
   (openbank-libs-runtime) is backed by a same-namespace `redis` Deployment/Service (`redis.yaml`,
   issue #1354). Ephemeral by design (persistence disabled) — a restart clears in-flight pending
   approvals, matching the TTL-bounded residual-risk posture in §7. Reachable only from
   `lending-service` (generated NetworkPolicy `redis-ingress-allow-list`, ADR-0081). Holds
   short-lived `PendingApproval` records only — no loan, balance or PII data.

## 3. Controls in place (this slice)

- **Authn/z.** Every endpoint `@RolesAllowed` with the appropriate role; sensitive reads (loan,
  schedule, arrears, impairment) gated to lending/risk/compliance/audit and audit-logged.
- **Server-side acting principal.** The acting principal for every state-changing endpoint
  (apply, decide, disburse, write-off) is the authenticated JWT subject via `actor()` — **never** a
  client-supplied field. (Write-off was hardened to this in PR #158.)
- **Four-eyes (maker-checker).** A credit decision must be made by a principal *different* from the
  application maker; disbursement by a principal different from the approver; a registered collateral
  item by a principal different from its registrant (issue #621, see §7). Enforced server-side in the
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
  - **Closed (issue #621) — collateral registration is now four-eyes-gated.** `POST .../collateral`
    (`lending.collateralRegister`) creates a `Collateral` in `PENDING` status; `applyCollateral` only
    sums `APPROVED` items into the LGD adjustment. A different principal must decide it via `POST
    /api/v1/lending/collateral/{id}/decision` (`lending.collateralDecide`) before it can reduce a loan's
    reported ECL — mirrors the `lending.approve`/`decide` origination control (§7 for the STRIDE
    supplement; this is the domain-level gate, independent of `authz.four-eyes.enforce`).
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
| **Elevation of privilege** | Mitigated | No `@PermitAll`; least-privilege roles per endpoint; four-eyes prevents single-actor origination, disbursement, and (issue #621) collateral registration from reducing reported ECL. |

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
- **Collateral registration four-eyes: closed at the domain level, REST-layer pause not yet enforced
  (issue #621).** `LendingService.decide()`/`applyCollateral()` always require a distinct checker before
  a `Collateral` counts toward LGD — this holds regardless of `authz.four-eyes.enforce`. What remains a
  deliberate, tracked follow-up (same as `lending.disburse` today, §7): flipping
  `authz.four-eyes.enforce=true` so the REST call itself pauses (HTTP 202 + `PendingApproval`) rather than
  the maker having to separately call the decide endpoint out-of-band. Until that flip, the maker's `POST
  .../collateral` call still returns 201 immediately (the resulting row is `PENDING` and inert for ECL
  purposes) rather than 202-pausing — a process/runbook gap, not a control gap: the data cannot reduce
  provisioning without a second approval either way.
- **No collateral valuation staleness bound.** `marketValue`/`haircut` are read as-declared at
  registration time with no expiry, re-validation, or staleness flag on the LGD reduction — see §3 and
  the ADR-0028 delivery note.
- **Haircut calibration is a placeholder, not a risk model.** Per-`CollateralType` haircuts are supplied
  by the caller at registration time (`CollateralRequest.haircut`) with no platform-enforced or
  actuarially-derived table — see the ADR-0028 delivery note's explicit non-calibration caveat.
- **Reschedule/restructuring has no maker-checker control (issue #667/#668).** Unlike origination
  (proposer≠decider) and collateral (registrant≠decider), `RescheduleLoanUseCase.reschedule` is a
  single-actor action — the same shape as `WriteOffLoanUseCase.writeOff`, which has the same gap.
  A `ROLE_CREDIT_RISK` identity can unilaterally forgive principal and rewrite a loan's remaining
  schedule. Given `principalForgiveness` is a genuine, unbounded credit-loss event, adding a
  four-eyes decision step (mirroring the collateral `register`/`decide` split) is a tracked
  follow-up, not done in this increment — flagged honestly rather than silently accepted as
  equivalent to write-off's existing (also unaddressed) gap.

## 6. Out of scope

Infrastructure-layer controls (network egress, secret storage, runtime isolation) are covered by the
platform substrate (ADR-0027) and the unified authz layer (ADR-0034), not duplicated here.

## 7. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /applications/{id}/disburse` (`lending.disburse`) and, as of issue #621, `POST
/loans/{id}/collateral` (`lending.collateralRegister`) are the money-path/provisioning-integrity
actions wired into the shared, opt-in ADR-0155 four-eyes mechanism (rollout tracked in issue #413;
sepa-payment's `PATCH /status` was the pilot). Disbursement books the loan and moves funds
(`LedgerCallGuard` → `ledger-service`); collateral registration feeds the IFRS 9 LGD adjustment
(§1, §3) — both are framed here as actions where a single actor's data can misstate the bank's
financial position. New endpoint `PATCH /api/v1/lending/approvals/{id}` lets a DIFFERENT lending
officer decide the resulting `PendingApproval` for disbursement; the maker retries with an
`X-Approval-Id` header. **`authz.four-eyes.enforce` stays `false`** — the `ApprovalStore`/endpoint
are wired, but blocking the REST call itself (pause-and-resume) is a deliberate follow-up flip, not
bundled here (see ADR-0155).

As of issue #1354 the `RedisApprovalStore` is now backed by a real in-namespace Redis (§2 item 6);
previously the bean resolved but pointed at `redis://localhost:6379` in-pod, so flipping
`authz.four-eyes.enforce=true` would have failed every gated `lending.disburse`/
`lending.collateralRegister` call on a connection refusal — a latent fail-closed money-path outage
rather than the documented pause-and-resume. The Redis backing is a precondition for that flip; the
flip itself remains a separate deliberate step, validated against this Redis in a lower env first.

Collateral registration additionally has its **own, independent, always-on domain-level gate** that
does not depend on `authz.four-eyes.enforce` at all: `LendingService.register()` always creates a
`Collateral` in `PENDING` status, and `applyCollateral()` only ever sums `APPROVED` items. A
distinct checker decides it via `POST /api/v1/lending/collateral/{id}/decision`
(`lending.collateralDecide`), which enforces `decidedBy != registeredBy` the same way `decide()`
enforces `decidedBy != proposedBy` for origination (§3). This is deliberately a second, belt-and-braces
layer beneath the shared ADR-0155 REST-pause mechanism — even with `authz.four-eyes.enforce=false`
fleet-wide (and even if the OPA sidecar / `ApprovalStore` were entirely unavailable), a single maker
cannot make their own collateral registration count toward a loan's ECL.

Note: lending already has an *application-level* maker-checker control on the origination
decision (`lending.approve`, §3 above — decide must differ from the application's maker), and now
an equivalent one on collateral (`lending.collateralDecide` — decide must differ from the
registrant). The ADR-0155 mechanism here is a further, independent control specifically on
**disbursement** and (once flipped) the REST-pause UX for **collateral registration**: even after a
valid checker decision, the shared `ApprovalStore`/`AuthorizeInterceptor` path can additionally gate
the triggering REST call itself (currently advisory, `enforce=false`).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an authorized role decides an approval/decision | `@RolesAllowed("ROLE_LENDING_OFFICER","ROLE_ADMIN")` on the ADR-0155 decide endpoint; `@RolesAllowed("ROLE_CREDIT_RISK","ROLE_ADMIN")` on `lending.collateralDecide` + OPA `@Authorize` on both |
| **E**oP | The maker approves their own disbursement or collateral registration (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (403) when `decidedBy == makerId` for the ADR-0155 path; `LendingService.decide()` for collateral throws `IllegalStateException` (409, "Four-eyes violation") when `decidedBy == registeredBy` — enforced in the domain layer itself, not just the REST layer, for both |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different disbursement | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding. Collateral's own decide endpoint has an analogous one-time-use guard: `LendingService.decide()` rejects a collateral not currently `PENDING` (409, "not awaiting a decision") |
| **R**epudiation | No record of who approved a gated disbursement or collateral registration | `PendingApproval.decidedBy`/`decidedAt` (Redis, TTL-bounded — see Residual risks below); `Collateral.decidedBy`/`decidedAt` (Postgres, permanent — no staleness/TTL concern for this path) |
| **I**nfo disclosure | Approval id enumeration reveals loan/action metadata to an unauthorized caller | `decide` requires the caller to already hold a valid, role-gated session; the ADR-0155 approval id is a random UUID (`RedisApprovalStore`, not sequential); the collateral id is the `CollateralId` already returned to the (authorized) registrant |
| **D**oS | Flooding the gated endpoints to exhaust Redis with pending approvals, or the DB with PENDING collateral rows | Bounded by the same rate-limit controls as the gated endpoint itself; each ADR-0155 `PendingApproval` is TTL-bounded (86400s); a PENDING `Collateral` row has no TTL but is bounded by ordinary row-count/rate limits, same as any other write |

**DFD update:** adds `Lending officer (checker) → PATCH /api/v1/lending/approvals/{id} → Redis
(approval:*)` alongside the existing `POST /applications/{id}/disburse` edge (the maker's retry
reuses the existing DFD edge); adds `Credit-risk officer (checker) → POST
/api/v1/lending/collateral/{id}/decision → Postgres (collateral.status)` alongside the existing
`POST /loans/{id}/collateral` edge.
**Risk class:** integrity (segregation of duties: disbursement, collateral registration) +
confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the ADR-0155 endpoint/store exist but do
not change any existing request's outcome until explicitly flipped. The collateral domain-level
gate (`PENDING` until decided) is NOT behind this flag and has no rollback toggle short of
reverting the commit — see the ADR-0028 delivery note and §8.

**Residual risk:** four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent
audit trail (ADR-0155) — a durable-audit requirement for "who approved what, forever" would need
an additional store; not implemented in this PR. (The collateral decision itself, unlike the
ADR-0155 `PendingApproval` wrapper, IS durably recorded in Postgres via `Collateral.decidedBy`/
`decidedAt` — this residual risk is scoped to the Redis-backed ADR-0155 layer only.)

## 8. Compliance pack activation (ADR-0212) — STRIDE supplement

`POST /api/v1/lending/compliance-packs/proposals` + `/proposals/{id}/decide` put the legal rule
set of every origination under a runtime four-eyes gate: a compliance maker proposes a pack, a
DIFFERENT compliance principal activates it. The pack the origination guard (fail-closed,
ADR-0212 D2, behind `lending.compliance.enforce-pack`) refuses or accepts loans by is therefore
not a deployable artifact a single developer or a CI job can change — it is data that requires
two authenticated principals to alter.

| Threat | Mitigation |
|---|---|
| Tampering — one actor weakens a jurisdiction's rules (affordability floor, cooling-off, termination caps) | MakerChecker four-eyes in code (maker ≠ checker, `MakerCheckerViolation`); activated versions immutable (re-activation refused); strict closed-schema parser + ~15 compile-time invariants reject malformed or unlawful packs before any human sees them |
| Information disclosure — pack contents leak | Packs are reference data, not secrets; read endpoints role-gated (`ROLE_COMPLIANCE`/`ROLE_CREDIT_RISK`/`ROLE_LENDING_OFFICER`) |
| Repudiation — "who activated this rule set?" | Durable Postgres record per activation (`compliance_pack_activation`: maker, checker, reason, timestamps) + canonical SHA-256 `content_hash` pinned into the audit evidence (ADR-0214); unlike the Redis-backed ADR-0155 layer this trail is permanent |
| DoS — boot bricked by a corrupt activation row | Deliberate fail-loud: boot refuses to start over a corrupt activation rather than originate unprotected; operator remediation is to fix the row (the in-memory registry cannot silently run with a partial rule set) |
| Elevation of privilege — maker self-approves | Server-side identity from the JWT subject (never request body); segregation enforced twice: `Proposal.approve` and registry re-assertion |

**Residual risk:** pack enforcement ships behind the bootstrap flag (default `false`) until the
CZ reference pack is seeded and activated — the guard cannot protect origination while off.
Tracked as the named bootstrap follow-up (ADR-0212 D4).

## 9. Customer self-service origination intake (ADR-0211) — STRIDE supplement

`CustomerIntakeResource` (`POST /api/v1/lending/intake/applications`) is the first path by which a
request originating **outside the bank's staff** reaches the loan book. Until it existed, every
origination entry point was a desk endpoint, and the whole §4 posture assumed an authenticated
employee behind every write. That assumption no longer holds, so this supplement states what does.

**What it is not.** It is the MAKER leg only. The created application enters the ordinary
origination graph and still requires a human checker (`lending.approve`, four-eyes) before a
disbursement moves any money. A customer cannot originate cash by calling this endpoint, and the
§7 controls are untouched.

**Why `@RolesAllowed` cannot be the control here.** customer-edge's M2M identity carries
`ROLE_OPERATOR` and nothing else, and `AuthorizeInterceptor` classifies a client_credentials JWT as
`HUMAN` — so neither the JAX-RS role gate nor the lending rego extension (whose
`operator-lending-write` rule admits any `lending.*` action for `ROLE_OPERATOR`) can distinguish the
edge from a real person at a desk. The control is therefore a named-principal check in the handler
against `lending.intake.caller-principal`, which refuses every call when unset.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | An operator (or any `ROLE_OPERATOR` holder) files an application in a customer's name | Handler compares `SecurityIdentity.principal.name` to `lending.intake.caller-principal` and refuses on mismatch; an unset value refuses everything rather than admitting any operator. Covered by `CustomerIntakeResourceTest` (the test suite goes red when the check is removed). |
| **S**poofing | A customer applies on behalf of another party | The party id comes only from `X-Customer-Party-Id`, which customer-edge derives from the customer JWT's `party_id`/`sub`; the request body has no party field at all, and the nil UUID is refused. |
| **T**ampering | An applicant prices their own loan, or picks the compliance regime that judges them | `nominalAnnualRate`, `jurisdiction`, `productType` and `currency` are server configuration, absent from the request schema. An unpriced product refuses (403) rather than defaulting a rate. |
| **T**ampering | An applicant bypasses the ADR-0212 pack guard by declaring a jurisdiction with no active pack | Same as above — jurisdiction is not caller-supplied, so the (jurisdiction, productType) key the guard resolves is fixed by configuration. |
| **R**epudiation | "Did the customer really ask for this?" | The maker is recorded as `customer:<partyId>`, namespaced so it can never be mistaken for a desk principal in the ADR-0214 evidence trail nor satisfy a checker leg; customer-edge emits `LOAN_APPLICATION_SUBMITTED` to the audit stream with the upstream status. |
| **D**oS | An app floods intake, filling the application table and the operator queue | Bounded by customer-edge's `RateLimitFilter` on the customer-facing side and by the amount/term bounds here; the feature defaults OFF (`lending.intake.enabled=false`), so the exposure exists only once deliberately enabled. Per-party application-rate limiting is a gap — see below. |
| **E**oP | Self-service intake becomes a route to disbursement | It is not: intake creates an application, never a `Loan`; `lending.disburse` remains a separate four-eyes desk action. |

**Residual risks / gaps (not closed by this change):**

- **No per-party intake rate limit in lending-service.** The only throttle is customer-edge's
  generic filter. A compromised edge, or a second future caller, could enqueue applications faster
  than the desk can dispose of them. Bounded in practice by the feature defaulting off.
- **No SCA on submission.** ADR-0211 puts SCA on the *signature*, not the application, so this is
  by design — but it means an attacker holding a live customer session can file an application in
  that customer's name. The consequence is an entry in the operator queue, not money movement.
- **The rego rule is documentation, not enforcement.** `edge-customer-intake` names the intended
  caller, but `operator-lending-write` already grants the same action more broadly; the rule only
  becomes load-bearing if that blanket operator grant is narrowed.

## 10. Change log

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `partyId` on the two list endpoints (applications, loans). These are read-only queries scoped BY that party, so a null reaching the repository is a query with no subject; the handlers are non-suspend, so in practice it threw at the boundary and 500'd first. The `@Authorize(action = "lending.list")` gate is unchanged and still runs before the guard. No new caller or boundary. Rollback: revert.
- **2026-07-31** — Termination and early-exit lifecycle (ADR-0215): termination
  sub-lifecycle states + guard, settlement quote (expired quote refused), statutory
  withdrawal with unwind journal + day interest, DPD/CRR-178 default gates, mandatory
  forbearance gate, four-eyes bank termination (CREDIT_RISK maker, COMPLIANCE checker),
  notice-period acceleration, collateral release with closure, credit.loan.transition
  evidence. V10 migration.
- **2026-07-31** — Credit evidence emission (ADR-0214): canonical
  credit.application.transition events in the transactional outbox (PII-minimised:
  ids, versions, hashes), evidence bundle endpoint GET /applications/{id}/evidence
  (ROLE_COMPLIANCE/ROLE_AUDIT), audit-service consumes openbank.lending.events.
- **2026-07-31** — Temporal durable origination timers (ADR-0211 D2): per-application
  OriginationTimersWorkflow (document SLA with half-time reminder, offer expiry,
  reflection/cooling-off wait), generation-counter invalidation, idempotent expire/advance
  activities against the aggregate, build-gated Temporal adapter with offline no-op default.
- **2026-07-31** — Canonical origination graph wired (ADR-0211): V8/V9 status migration
  (PROPOSED→SUBMITTED, APPROVED→OFFERED, REJECTED→DECLINED), state machine guards on
  apply/decide/disburse, `POST /applications/{id}/advance`, sandbox STP flag (never prod).
- **2026-07-31** — CZ reference pack (`compliance-packs/cz-consumer-credit-v1.json`) with a
  rot-guard test + activation runbook. Enforcement flag stays `false` until the pack is
  four-eyes-activated per environment (ADR-0212 D4 bootstrap order: seed → activate → verify → flip).
- **2026-07-30** — Compliance pack four-eyes activation (ADR-0212 slice 1): `compliance_pack_activation`
  table (V7), boot rehydration of the in-memory registry, admin propose/decide endpoints, fail-closed
  origination guard behind `lending.compliance.enforce-pack` (default off — bootstrap). Threat
  supplement in §8.
- **2026-07-22** — Deploy an in-namespace Redis backing the four-eyes `ApprovalStore` (issue #1354).
  The CDI bean already resolved (`ApprovalConfig` @Produces `RedisApprovalStore`), so
  `AuthorizeInterceptor`'s "no store → log + proceed" branch was never taken for lending; the store
  pointed at `redis://localhost:6379` in-pod. Flipping `AUTHZ_FOUR_EYES_ENFORCE=true` would have
  failed every `lending.disburse`/`lending.collateralRegister` on a connection refusal — a latent
  fail-closed money-path outage. Adds `redis.yaml` (Deployment+Service, mirroring balances) +
  `QUARKUS_REDIS_HOSTS` env; regenerated `network-policies.yaml` (new `redis-ingress-allow-list`).
  **New trust boundary added to §2 (item 6); §7 updated.** `authz.four-eyes.enforce` stays `false`
  — enforcement flip is a separate deliberate step, validated against this Redis in a lower env
  first. No app-code or DB schema change (gitops + threat-model only). Rollback: revert the commit.
- **2026-07-22** — Multi-currency GL posting correctness (issue #1275). The loan book posts in the
  loan's own (client-supplied) currency, but the GL accounts were fixed to CZK (V19 +
  `LendingLedgerConfig.Gl` `@WithDefault` UUIDs), so the first EUR/USD/GBP disbursement would 422 at
  ledger-service (line/account currency-match check) — a money-path **availability** defect on the
  ledger crossing (§2 boundary 2), invisible to unit tests (pure factory), the outbox IT
  (dispatch-disabled) and the CZK-fixed pact. Fix: `LendingGlChart` selects the per-currency leaf set
  by loan currency and **fails loud** on an unseeded currency (no silent mis-post); ledger migration
  `V20` seeds the EUR/USD/GBP leaves; funding-clearing reuses the shared per-currency Customer Cash
  Clearing accounts. The `@WithDefault` placeholders — which let the service boot green and 422 at
  first posting rather than failing loud — are removed (accounts are now platform-fixed in code, like
  transaction-service's `PaymentJournalFactory`). No trust-boundary change; no new external surface.
  Rollback: revert the commit (safe only before a real non-CZK entry references the V20 accounts).
- **2026-07-09** — Reschedule/restructuring (ADR-0028 follow-up, issue #667/#668). New
  `RescheduleLoanUseCase` + `POST /api/v1/lending/loans/{id}/reschedule`
  (`lending.reschedule`, `ROLE_CREDIT_RISK`/`ROLE_COMPLIANCE`/`ROLE_ADMIN`). Deletes an ACTIVE
  loan's remaining UNPAID installments and replaces them with a new schedule generated from the
  outstanding balance (net of an optional `principalForgiveness`) via the same `Amortization`
  primitive origination uses; already-paid rows are never touched, and new rows continue the
  installment numbering after the last paid one so a future repayment's ledger reference can never
  collide with an already-posted one. A positive `principalForgiveness` books a new
  `RESCHEDULE_FORGIVENESS` `PostingKind` — same GL accounts as `WRITE_OFF`, kept distinct only for
  audit-trail attribution. The loan's `version` is bumped and persisted on every reschedule as the
  durable half of the forgiveness posting's idempotency key. **New asset row added to §1**
  (Restructuring authority); **new roadmap gap added to §5** (no maker-checker control, same
  unaddressed shape as `WriteOffLoanUseCase`) — flagged honestly, not closed by this change. No new
  trust boundary (same ledger/outbox crossings as write-off); no DB schema change (reuses the
  existing `installment` table). Rollback: revert the commit.
- **2026-07-09** — Collateral registration four-eyes (ADR-0028 follow-up, issue #621), closing the
  gap PR #607's threat-model update flagged. `Collateral` gains `status`
  (`PENDING`/`APPROVED`/`REJECTED`), `registeredBy`, `decidedBy`, `decidedAt` (Flyway `V5`, existing
  rows backfilled to `APPROVED`). New endpoint `POST /api/v1/lending/collateral/{id}/decision`
  (`lending.collateralDecide`, `ROLE_CREDIT_RISK`/`ROLE_ADMIN`) lets a DIFFERENT principal decide a
  `PENDING` registration; `LendingService.decide()` enforces `decidedBy != registeredBy`
  domain-side (409 on violation). `applyCollateral()` now filters to `APPROVED` only — a `PENDING`
  or `REJECTED` item cannot reduce a loan's ECL. `lending.collateralRegister` (the registration
  action's new `@Authorize` verb, replacing the previous `lending.create`) added to
  `rules.yaml: four_eyes.verbs` so the shared ADR-0155 REST-pause mechanism recognizes it the same
  way it already recognizes `lending.disburse` — `authz.four-eyes.enforce` stays `false` (unchanged
  fleet convention), so this PR does not change whether the REST call itself pauses; the
  domain-level PENDING-until-decided gate is independent of that flag and is always on. Updated §1
  (Collateral register asset), §3 (control block closed), §4 (EoP row), §5 (roadmap item
  downgraded from "no control" to "REST-pause enforcement flip still pending, domain gate closed"),
  §7 (STRIDE supplement extended to cover collateral alongside disbursement). Rollback: revert the
  commit (`V5`'s own rollback note drops the new columns/type; pre-migration behavior returns).
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
- **2026-08-01** — ADR-0211 customer self-service intake. `CustomerIntakeResource`
  (`POST /api/v1/lending/intake/applications`) is the first entry point on this service reachable
  from outside the bank's staff, so §4's "an employee is behind every write" assumption no longer
  holds unqualified — new §9 states what replaces it. The control is a named-principal check in the
  handler, NOT `@RolesAllowed` and NOT rego: customer-edge's M2M token carries `ROLE_OPERATOR` and
  the interceptor classifies it as `HUMAN`, so neither layer can tell the edge from a person.
  Default OFF (`lending.intake.enabled=false`) and refuses everything while
  `lending.intake.caller-principal` is unset. No DB schema change; maker leg only, so the four-eyes
  disbursement control is untouched; rollback = revert the commit or leave the flag false.
