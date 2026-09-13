---
date: 2026-08-20
decision-status: accepted
delivery-status: shipped
authors: [Claude (paired with Jiří Raška)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [aml-sanctions, compliance, onboarding]
summary: "openbank.aml.auto-clear stays a sandbox-only straight-through AML flag, default false, and this records that the AML four-eyes control it bypasses is today a state machine plus a caller-supplied decidedBy, not a maker-checker gate."
---

# ADR-0268 — Sandbox straight-through AML: `openbank.aml.auto-clear`

## Context

`openbank-aml-service`'s `PartyEventConsumer` opens a `CUSTOMER_ONBOARDING` AML case on every
`PARTY_CREATED` event. When `openbank.aml.auto-clear` (env `OPENBANK_AML_AUTO_CLEAR`) is `true`
it immediately drives that case to `CLEARED` with `decidedBy = "SANDBOX_SYSTEM"` and
`assignedAnalyst = "SANDBOX_BOT"`, so the party clears the AML key of the ADR-0266 two-key
activation gate without any analyst. It defaults to `false` in code and is set `true` in the
sandbox gitops manifest (`openbank-infra/gitops/components/aml/aml-service.yaml`).

It has an exact twin — `openbank.kyc.auto-approve`, the KYC key of the same gate — and that twin
**is** decided, by ADR-0116 §4. This one was not decided anywhere. Its entire written record was a
KDoc sentence in `PartyEventConsumer`, a default value, and this service's own `src/main/resources/docs/`.
The gitops comment next to it cited ADR-0073, which is hardware-backed credential storage for the
mobile app — an unrelated ADR (corrected under #5785).

Two halves of one activation gate should not have two different standards of governance. This ADR
supplies the missing half, and — because writing it required reading the control it claims to
bypass — records what that control actually is today rather than repeating the service docs' claim.

## Decision

**1. The flag stays, sandbox-only, default `false`.**

`openbank.aml.auto-clear` is a SmallRye config property (not an OpenFeature flag, ADR-0067), so
changing it requires a restart. Default `false` in `PartyEventConsumer`'s `@ConfigProperty`. It is
set `true` only in the sandbox environment, whose purpose is a demonstrable end-to-end onboarding
journey with no staffed AML desk. This mirrors ADR-0116 §4 for the KYC twin, deliberately: one gate,
one shape.

**2. What enforces "sandbox-only" today is the default and the deployment value — and nothing else.**

This is stated as a limitation, not a control. There is no admission policy, no CI gate and no
boot-time refusal that would stop `OPENBANK_AML_AUTO_CLEAR=true` reaching a production namespace.
The repo describes exactly one deployed environment (sandbox), so a CI gate asserting "no non-sandbox
manifest sets it true" would today pass over an empty set — a check that cannot fail is not a control,
and this ADR declines to record one as if it were. The enforcing control is a **production
prerequisite** (§5), owed before any environment other than sandbox exists.

**3. The auto-clear leaves a record, and the record names itself.**

The auto-clear path is not silent. It goes through the same `AmlCaseService.updateDecision` as an
analyst decision, so it writes the `aml_cases` row (`decided_by = 'SANDBOX_SYSTEM'`,
`assigned_analyst = 'SANDBOX_BOT'`, `decision_reason = 'Sandbox auto-clear (no adverse match)'`),
emits `aml.case.status_changed.v1` through the transactional outbox (ADR-0050) carrying those same
fields, and logs a line. The sentinel `decidedBy` is what makes an auto-clear queryable after the
fact and separable from a human decision — the same role `reviewedBy = "sandbox-auto-approval"`
plays for the KYC twin. A terminal AML decision attributed to `SANDBOX_SYSTEM` in a non-sandbox
environment is a compliance incident, and that string is the detector for it.

**4. The four-eyes control this flag bypasses is nominal, and calling it four-eyes was an overclaim.**

`openbank-aml-service`'s own compliance doc said the decision endpoint gives "four-eyes
accountability via `decidedBy`/`assignedAnalyst`". Measured against the code, it does not:

- `decidedBy` arrives in the **request body** (`UpdateAmlDecisionRequest.decidedBy` →
  `toCommand`), never from the authenticated security context. The only validation is
  `require(decidedBy.isNotBlank())`. Any single caller holding `ROLE_OPERATOR` can clear a case and
  type any name into the attribution field. ADR-0116 §3 mandates the opposite for the KYC twin —
  "the reviewer identity must come from the authenticated security context — it is never accepted
  from the request body".
- `openbank-aml-service` is not in `rules.yaml: money_path_services`, so `rest.rego`'s
  `four_eyes_required` clause can never derive an `aml` action scope. No verb addition could reach
  `amlCase.updateDecision`.
- The state machine permits `OPEN → CLEARED` directly, so there is not even the state-based
  opener/reviewer split ADR-0116 §3 relies on for KYC.
- `AUTHZ_ENFORCE` is `false` for this service in gitops (deliberately, per issue #1797), so
  `@Authorize(action = "amlCase.updateDecision")` is advisory — it logs "would DENY" and blocks
  nothing.

So the honest compliance argument for the flag is **not** "a sandbox affordance that bypasses a
strong control". It is: in the sandbox there is no AML desk, and the control it skips is in any case
a single-operator endpoint with self-declared attribution. Closing that gap is §5, and it is a
larger piece of work than this flag.

**5. Production prerequisites.** Before any non-sandbox environment runs this service, all of:
`decidedBy` sourced from the security context and never the body; a maker-checker separation for
terminal AML decisions; `AUTHZ_ENFORCE=true` with the deny population confirmed empty; and a
deployment-time assertion that `OPENBANK_AML_AUTO_CLEAR` is not `true`. Tracked as the follow-up on
issue #5837 — this ADR decides the flag, it does not build the gate.

## Alternatives considered

- **Add a `§` to ADR-0116 instead of a new ADR.** Genuinely considered, and it is what issue #5837
  leans toward — the two flags are one gate, and a single record is arguably the honest shape.
  Rejected because ADR-0116 is titled and scoped to the KYC engine (its lifecycle, its five checks,
  its role split); appending an AML section makes its title false and buries the AML decision where
  nobody auditing AML would look. Cross-referenced in both directions instead.
- **Delete the flag and seed sandbox AML clearances out-of-band** (a seed script calling the decision
  endpoint, as `seed-test-customer.sh` does for KYC). Rejected: it moves the same bypass to a script
  with *less* attribution — the seed would post a decision indistinguishable from an analyst's,
  whereas the flag's `SANDBOX_SYSTEM` sentinel is self-labelling.
- **Refuse to start when the flag is `true` unless an explicit sandbox marker is present**
  (`@Startup` guard; `@ApplicationScoped` alone is lazy and would never run). Not rejected on the
  merits — deferred to §5, because the repo has no trustworthy "which environment am I" signal today
  and inventing one here would produce a guard that passes because it cannot fail.

## Consequences

**Positive**
- The AML half of the activation gate now has a decision record, at the same standard as the KYC
  half, so the gitops comment has something correct to cite.
- The four-eyes overclaim is retired in writing before an auditor finds it. `SANDBOX_SYSTEM` is
  recorded as the detector for a mis-set flag.

**Negative**
- The bypass is documented, not closed. §5 lists four prerequisites, none of which is built.
- "Sandbox-only" remains an operational convention. Nothing mechanical prevents the flag being
  `true` elsewhere.

**Neutral**
- No behaviour change to the flag itself: same default, same sandbox value, same code path.

## Compliance impact

- PCI DSS: not applicable — no cardholder data on this path.
- DORA:    not applicable — this is a control-design record, not an ICT resilience decision.
- GDPR:    not applicable — the auto-clear writes no personal data beyond the case row the
           `PARTY_CREATED` consumer already opens.
- PSD2:    not applicable — AML case decisioning is not a payment-initiation or SCA control.
- CNB:     engaged — Czech AML Act No. 253/2008 Coll. requires an auditable trail for customer
           screening decisions. The flag produces a decision attributed to a non-human
           (`SANDBOX_SYSTEM`); §1 confines that to the sandbox and §5 states what is owed before
           any other environment. §4 records that the four-eyes separation is not technically
           enforced for AML today.

## References

- [ADR-0116](0116-kyc-engine-risk-checks-and-four-eyes-gate.md) §3–§4 — the KYC twin
  (`openbank.kyc.auto-approve`) and the reviewer-identity rule this ADR measures AML against
- [ADR-0266](0266-mgm-fixed-reward-growth-incentives.md) §2 — the KYC+AML two-key activation gate
- [ADR-0050](0050-regulatory-grade-outbox-dispatch.md) — the outbox the status-changed event
  commits through
- [ADR-0034](0034-unified-opa-authz-mcp-and-rest.md) — the `@Authorize` PEP that is advisory here
- Issue #5837 — this gap; #5785 / #5836 — the ADR-0073 miscitation sweep that surfaced it
