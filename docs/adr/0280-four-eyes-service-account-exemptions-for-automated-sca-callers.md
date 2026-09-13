---
date: 2026-09-03
decision-status: proposed
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [security, authz, sca, governance]
summary: "Four-eyes gains per-action service-account exemptions (rules.yaml four_eyes.exemptions), so device.enroll and scaChallenge.consume are gated for human callers while verified SCA automation keeps flowing."
---

# ADR-0280 — Four-eyes service-account exemptions for automated SCA callers

## Context

The #938 four-eyes sweep assessed sca-service and recorded a stalemate in
`rules.yaml: money_path_services`: `device.enroll` and `scaChallenge.consume` are the riskiest
operator-reachable actions in the service (enroll a credential on someone else's device; spend an
approved challenge outside a payment ceremony), yet they were left UNGATED because
`four_eyes_required` is computed by action name alone, with no caller awareness — and both actions
have confirmed M2M callers that must not pause:

- `service-account-openbank-edge` (customer-edge) drives the ordinary customer SCA ceremony:
  `scaChallenge.consume` is the ADR-0021 settlement gate every real payment passes through, and
  `device.enroll` is customer self-service device enrollment.
- `service-account-openbank-services` (shared backend client) consumes challenges for the
  delegation grant-accept and document-signing ceremonies (#3734).

Gating the verbs for everyone would brick that automation the day `authz.four-eyes.enforce` is ever
flipped on (ADR-0155); leaving them ungated forever means the human ops-console path
(`operator-sca-write` grants both families to any operator/admin) never gets maker-checker. Issue
#8360 called the stalemate what it is: a standing security exception with no end date.

## Decision

We will make four-eyes **caller-aware through an explicit exemption list**, and then gate the two
SCA actions:

1. `rules.yaml` gains `four_eyes.exemptions`: a map of exact action name → list of principal ids
   for which `four_eyes_required` does **not** fire. Absence of the key (older bundles) or of an
   action entry means no exemptions — Rego membership over an undefined collection simply does not
   fire, so this is additive and backward-compatible, the same shape as `four_eyes.actions`
   (ADR-0176 D5).
2. `rest.rego` evaluates the exemption in the four-eyes clauses: an action is flagged
   `four_eyes_required` only when the caller's `principal.id` is not in that action's exemption
   list.
3. `device.enroll` and `scaChallenge.consume` are added to `four_eyes.actions` (exact actions, not
   verbs — no other fleet action ends in `enroll`/`consume` today, and exact listing keeps any
   future `*.consume` automation action from being trapped by a verb suffix), with exemptions for
   `service-account-openbank-edge` (both actions) and `service-account-openbank-services`
   (`scaChallenge.consume` only — the shared client never enrolls devices).
4. Every non-exempt caller — in practice the human operator/admin ops-console path — is flagged
   four-eyes-required once enforcement is enabled for the service.

An exemption entry must name a verified, in-repo M2M caller (the `service-account-*` identity from
the service's own `*_rest_ext.rego`), never a role and never a human principal — the list is an
allowlist of *automation identities*, not a general escape hatch.

## Alternatives considered

- **Leave the stalemate** (status quo) — the stalemate is a standing exception: the operator
  console can enroll devices on behalf of any party and consume any approved challenge with a
  single privileged session. Rejected: that is precisely the collusion/fat-finger surface
  four-eyes exists for.
- **Distinct operator-only actions** (`enrollOnBehalf`, `consumeManual`) per the guardrail's own
  prescription — clean, but requires new endpoints, admin-ui work and an API-contract bump per
  service, and leaves today's already-granted operator path ungated until that ships. Kept as the
  prescribed pattern for actions whose risky and automated paths are genuinely different operations;
  here they are the SAME operation called by different principals, which is what exemptions model.
- **Exempt by caller category** (e.g. "any service-account-* is exempt") — rejected: that exempts
  every present and future M2M client including ones that should never touch these actions; the
  exemption must be per-action and per-identity to stay reviewable.

## Consequences

**Positive**
- The two highest-risk SCA operator actions become maker-checker the moment enforcement flips —
  the stalemate's end date is now "when `authz.four-eyes.enforce` is enabled", not "never".
- The mechanism is reusable: any future money-path action with a verified M2M caller can be gated
  for humans without pausing automation, ending the class of "assessed but not wired" exceptions.

**Negative**
- `four_eyes_required` is no longer a pure function of the action name; reviewers must read the
  exemptions map alongside the actions/verbs lists. Mitigated by keeping all three in one
  `four_eyes:` block and by rest_test.rego pins.
- The exemption trusts that `service-account-openbank-edge`'s client credentials stay
  customer-edge's alone; a leaked edge client secret would bypass four-eyes on these two actions
  (it would already bypass everything else the edge can do — no new privilege class is created).

**Neutral**
- No runtime effect until `authz.four-eyes.enforce` is enabled (off fleet-wide today, ADR-0155);
  the flag now reaches the interceptor with caller awareness.
- `rules-opa-data.yaml` and every service bundle are restamped (the standard shared-policy roll).

## Compliance impact

- PCI DSS: not applicable — no cardholder data surface.
- DORA: supports strong access-control and operations-security expectations for privileged
  operations (maker-checker on credential enrollment and challenge consumption).
- GDPR: not applicable — no processing-purpose change.
- PSD2: supports SCA ceremony integrity (ADR-0021) — dynamic-linking challenges can no longer be
  spent by a lone operator session once enforcement is enabled.
- CNB: not applicable — no reporting or outsourcing change.

## References

- Issue #8360 (this resolution), #938 (original four-eyes sweep), #3734 (identity-scoped M2M rules)
- ADR-0021 (SCA ceremonies), ADR-0034 (unified OPA authz), ADR-0155 (four-eyes enforcement axis),
  ADR-0176 D5 (exact-action four-eyes list)
- `openbank-libs/governance/rules.yaml` (`four_eyes`), `policies/rest.rego`,
  `openbank-infra/gitops/components/sca/sca_rest_ext.rego`
