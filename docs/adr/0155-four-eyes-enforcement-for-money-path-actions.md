---
date: 2026-07-07
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, governance]
summary: "Enforce four-eyes on money-path REST calls via an opt-in two-phase approval flow: an ApprovalStore domain port with a Redis implementation, read by AuthorizeInterceptor, which forbids a maker from approving their own request."
---

# ADR-0155 — Four-eyes enforcement for money-path actions

> **Delivery update (2026-07-12).** All 11 money-path services with a
> `four_eyes.verbs`-matching action now have the `ApprovalStore`/decide-endpoint
> mechanism wired (issue #413): the original sepa-payment pilot plus batch 1
> (account, billing, clearing, domestic-payment, lending, sepa-instant, swift —
> PRs #556/#558–561/#563–564) plus batch 2 (ledger #929, balance #930,
> transaction #931 — the three services that also needed a first-ever Redis
> dependency added alongside the mechanism). `authz.four-eyes.enforce` stays
> `false` on every service — flipping it to actually block requests pending a
> second approval remains a deliberate, separate follow-up requiring the
> maker/checker runbook review this ADR's own Decision section calls for, not
> bundled into the wiring rollout. Decision-Status stays Proposed until that
> runbook review happens and enforcement is actually turned on somewhere.

## Context

ADR-0034 designed `four_eyes_required` as a flag OPA (`rest.rego`) computes for
money-path actions whose verb represents value movement (`transfer`, `post`,
`reverse`, `freeze`, `release`, `disburse`, ...), surfaced to the caller via
`AuthzDecision.attributes` so a handler can demand a second, distinct approver
(maker-checker) before the action takes effect.

A fleet audit (issue #395) found this was never wired end-to-end: the flag was
computed but never merged into the `allow` response object at all, 5 of 15
money-path services' real `@Authorize` action prefix didn't match the scope
`rest.rego` derives from `rules.yaml: money_path_services`, and most real verbs
didn't match the `four_eyes.verbs` vocabulary. A follow-up PR fixed all three —
`four_eyes_required` now correctly computes and is delivered to
`AuthzDecision.attributes` for every money-path action fleet-wide.

That PR deliberately stopped at the data path. Nothing in
`AuthorizeInterceptor` (openbank-libs-runtime) or any service handler had ever
read the flag — the actual second-approval control (blocking a maker's request
until a different, authenticated principal reviews it) has never existed
anywhere in the REST/human-actor plane. `MakerChecker`
(`openbank-libs-domain/src/main/kotlin/com/openbank/libs/analytics/MakerChecker.kt`)
and the agent-service `ProposalService` are a maker-checker mechanism, but for
a different actor plane entirely (AI-agent-proposed actions under ADR-0031,
reviewed by a human before the agent's action executes) — they are not reusable
here without adapting them to a human-maker / human-checker flow keyed by REST
action + resource rather than an agent's proposed tool call.

This ADR is the missing decision: how do we actually enforce four-eyes for a
human operator's money-path REST call, now that the signal computing it is
correct.

## Decision

We will add a REST-layer, opt-in, two-phase approval flow:

1. **Domain port** (`openbank-libs-domain/.../approval/ApprovalStore.kt`):
   `ApprovalStore` (`create`/`find`/`decide`/`markExecuted`) and a
   `PendingApproval` record (`id`, `action`, `resourceId`, `makerId`, `status`,
   timestamps). `decide` enforces segregation of duties itself — a principal
   can never approve/reject their own `PendingApproval` — so this invariant
   holds even if a service's REST layer forgets to re-check it.
2. **Redis-backed implementation** (`openbank-libs-runtime/.../approval/impl/RedisApprovalStore.kt`),
   mirroring the existing `RedisIdempotencyStore` per-service `@Produces`
   wiring pattern (not a libs-side `@ApplicationScoped` bean, so services
   without Redis are unaffected).
3. **`AuthorizeInterceptor` change**: when a decision carries
   `attributes.four_eyes_required == true` AND the service has opted in
   (`authz.four-eyes.enforce=true`, default `false`) AND an `ApprovalStore`
   bean is wired:
   - No `X-Approval-Id` request header → create a `PendingApproval` and throw
     (mapped to HTTP 202, body `{"status":"PENDING_APPROVAL","approvalId":...}`).
     The intercepted method is **not** invoked.
   - `X-Approval-Id` present → look up the approval; it must match this
     action + resource + the ORIGINAL maker's principal id, be `APPROVED`, and
     not already consumed. If valid, mark it `EXECUTED` (one-time use) and
     proceed. Otherwise, re-enter the pending-approval branch (defends against
     a stale/mismatched/replayed id silently proceeding).
   - Default (`authz.four-eyes.enforce=false`, or no `ApprovalStore` bean
     wired): unchanged behavior — proceed. This keeps the interceptor change
     safe to ship into every service via the shared libs JAR without
     retroactively blocking traffic anywhere that hasn't opted in.
4. **Per-service decide endpoint**: each participating service owns its own
   `POST .../approvals/{id}/decide` REST resource (own `@Authorize`,
   `@RolesAllowed`), calling the shared `ApprovalStore.decide`. Not a
   libs-auto-mounted endpoint — mounting a REST resource automatically for
   every service that merely depends on `openbank-libs-runtime` (i.e. the
   whole fleet) would expose a new endpoint on ~30 services that never opted
   in, for the benefit of the handful that have.
5. **Pilot**: `openbank-sepa-payment` (`sepaPayment.transitionStatus`), the
   concrete case from the #395 audit. `authz.four-eyes.enforce` stays `false`
   by default even here until the threat model + a maker/checker runbook are
   reviewed; flipping it is a follow-up, not bundled with this ADR's PR.

## Alternatives considered

- **Trust a client-supplied header as evidence of a second approval** (e.g. an
  `X-Approved-By` header the maker sets themselves). Rejected outright — it is
  trivially spoofable by the same caller, so it would be security theater, not
  a control. Never implemented, not even as a stopgap.
- **Reuse `MakerChecker`/`ProposalService` (agent-service, ADR-0031) as-is.**
  Rejected: that mechanism is keyed to an AI agent's proposed MCP tool call and
  lives in a separate service (`analytics-sink`) intended for the AI-actor
  plane. Bending it to also carry human REST actions would couple two
  independently-evolving planes (ADR-0034's own stated goal is the opposite —
  one shared OPA decision point for both planes, not one shared *workflow*
  engine) and would still need the same interceptor-level pause/resume logic
  this ADR adds regardless.
- **Pause-and-replay the original method invocation itself** (interceptor
  captures `InvocationContext` arguments, persists them, and later re-invokes
  the original business method once approved, so the maker never has to
  retry). Rejected: `InvocationContext` / CDI proxies are not serializable
  across an HTTP request boundary, and reconstructing a call this way turns
  the interceptor into a generic command bus — a much larger blast radius than
  the problem calls for. The maker retrying the same REST call with an
  `X-Approval-Id` header reuses the request they already have on hand and
  keeps the interceptor's job to "gate", not "replay business logic".
- **A libs-side auto-mounted `/api/v1/approvals/{id}` REST resource shared
  fleet-wide.** Rejected: every service depending on `openbank-libs-runtime`
  (the whole fleet) would gain a new endpoint whether or not it opted into
  four-eyes, for no benefit to services that never enable it — unnecessary
  attack-surface growth. Each participating service mounts its own resource
  instead (few lines, delegating to the shared `ApprovalStore`).
- **DB-table-backed `ApprovalStore` (Flyway migration per service).**
  Rejected for the pilot: `PendingApproval` is short-lived, bounded (TTL), and
  Redis is already wired into several money-path services for idempotency —
  reusing it avoids a schema migration per onboarding service. A durable-audit
  requirement (keep the record after the TTL for compliance review) is a
  plausible reason to revisit this later; not required for the pilot.

## Consequences

**Positive**
- The maker-checker control ADR-0034 always intended now has an actual
  enforcement path, not just a computed-but-unused flag.
- Opt-in and default-off: merging this changes no existing service's runtime
  behavior. A service turns it on only after wiring `ApprovalStore` and
  setting `authz.four-eyes.enforce=true`.
- Segregation of duties (maker != checker) is enforced in the domain port
  itself, not just at one call site — harder to accidentally bypass as more
  services onboard.

**Negative**
- A maker's client must handle a 202 response and retry with the approval id
  once a checker decides — this is a real UX/integration change for whatever
  calls the gated endpoint (ops dashboard, runbook script), not transparent.
- Redis-backed `PendingApproval` records are TTL-bounded, not a permanent
  audit trail; a regulator-facing "who approved what, forever" requirement
  would need a durable store in addition (open question, not solved here).
- Per-service decide endpoints mean N services each carry a small amount of
  near-identical REST/wiring code, rather than one shared implementation —
  accepted to avoid the fleet-wide-endpoint problem above.

**Neutral**
- Rollout to the other 14 money-path services is intentionally its own
  tracked follow-up (mirrors the ADR-0034 Phase 5 / issue #266 pattern for the
  advisory→enforce rollout), not bundled into this ADR's delivery.

## Compliance impact

- PCI DSS: not applicable (no cardholder data path).
- DORA: strengthens operational resilience controls around unauthorized
  change to payment processing (ICT risk management, dual control).
- GDPR: not applicable (no new personal-data processing; `makerId`/`decidedBy`
  are principal identifiers already processed elsewhere in the audit trail).
- PSD2 / RTS on SCA: supports the internal dual-control expectation for
  high-value/sensitive payment operations referenced in the sepa-payment and
  domestic-payment threat models (ADR-0030); does not itself implement
  customer-facing SCA.
- CNB: not applicable directly; supports the operational-control evidence
  expected in incident/audit reviews.

## References

- ADR-0034 (unified OPA authorization) — designed `four_eyes_required`.
- ADR-0030 (threat modeling for money-path services).
- ADR-0031 (AI-agent charter/MCP gate) — `MakerChecker`/`ProposalService` is
  the analogous mechanism for the AI-actor plane, not reused here (see
  Alternatives).
- Issue #395 — fleet audit that found the data path broken; the fix PR wired
  `four_eyes_required` into `allow.attributes`, fixed the 5-service scope
  mismatch, and extended the verb vocabulary.
- `docs/threat-models/openbank-sepa-payment.md` — updated alongside the pilot
  PR with this flow's STRIDE analysis.
