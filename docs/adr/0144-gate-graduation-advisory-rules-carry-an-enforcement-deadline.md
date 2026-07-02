# ADR-0144 — Gate graduation — advisory rules carry an enforcement deadline

Date: 2026-07-02
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

`openbank-libs/governance/rules.yaml` carries an `enforced` flag per rule
(`block` | `advisory` | `planned`), by design (ADR-0029 D7: a gate flips to
`block` only once its producing layer exists). That design is correct — but
it has no matching *exit* condition. Today the majority of rules sit at
`advisory`, including some whose producing layer has been "Shipped" for
months (e.g. `authz.enforce` is still `false` on every money-path service
per ADR-0034, even though the OPA sidecar, the `@Authorize` interceptor, and
the policy bundle are all live). An advisory rule with no deadline can stay
advisory forever without anyone deciding that on purpose — it just never
comes up again once the PR that shipped the producer merges.

The failure mode is specific: a rule can be *technically enforceable* and
*organizationally never enforced*, and nothing in the repo distinguishes
that state from "genuinely still blocked on a missing producer." An external
reviewer (or a regulator) reading `rules.yaml` today cannot tell the two
apart without cross-referencing every linked ADR's delivery status by hand.

## Decision

We will require every rule with `enforced: advisory` or `enforced: planned`
in `rules.yaml` to carry a `target_enforce_date` (ISO date) and a
`blocked_on` field naming the specific missing producer (an ADR id, a PR, or
a script path). A new CI check
(`.github/scripts/check-gate-graduation.sh`) fails the build when
`target_enforce_date` has passed and the rule is still not `block` — unless
the same PR that would otherwise fail also moves the date forward with a
one-line reason in the commit body. A rule may not be added to `rules.yaml`
without a `target_enforce_date`.

This does not change what any rule currently blocks. It changes what happens
when a producer ships and nobody flips the switch: today, nothing; after
this ADR, CI.

## Alternatives considered

- **Flip everything currently advisory to `block` immediately.** Rejected —
  several advisory rules (e.g. `api-contract` pending `oasdiff` wiring,
  `provenance` pending fleet-wide SBOM re-attestation per ADR-0030 D4) do not
  yet have a working producer; flipping them would break every open PR for a
  reason unrelated to the PR's content.
- **Leave it to periodic manual audit (e.g. quarterly governance review).**
  Rejected — this is the status quo, and it is exactly why 14 money-path
  services have sat at `authz.enforce: false` after their producer shipped.
  A rule without a forcing function decays to permanently advisory.
- **Track graduation dates in a separate tracking issue instead of in
  `rules.yaml`.** Rejected — `rules.yaml` is the declared single source of
  truth (rule #7, "derived data is never hand-edited" / "nothing computable
  is typed twice"); a parallel tracking issue would itself rot the way the
  governance manifest used to before ADR-0071.

## Consequences

**Positive**
- Advisory-vs-blocked-on-purpose becomes machine-checkable instead of
  requiring a human to read every linked ADR.
- Creates a forcing function for the highest-value backlog item this review
  surfaced: OPA enforcement on money-path services has no other trigger to
  ever happen.
- The escape hatch (move the date, state why, in the commit) is intentionally
  visible in `git log` rather than a silent config edit — anyone can audit
  how many times a deadline was pushed and why.

**Negative**
- Adds a new required CI check; a rule with a passed deadline and a
  legitimately-still-missing producer will fail CI until someone moves the
  date, which is friction by design but is still friction.
- The `blocked_on` field is free text validated only for presence, not for
  truthfulness — a rule can claim to be blocked on something that shipped
  weeks ago. The check catches *deadline* drift, not *justification* drift.

**Neutral**
- Does not change the `block` / `advisory` / `planned` vocabulary itself,
  only adds required metadata to the latter two states.

## Compliance impact

- PCI DSS: not applicable directly — indirectly strengthens evidence that
  Req. 6.2–6.3 controls (SSDLC gates) are not merely declared.
- DORA: Art. 6 (ICT risk management framework must be *effective*, not
  merely documented) — this closes the gap between "rule exists" and "rule
  runs."
- GDPR: not applicable.
- PSD2: not applicable directly.
- CNB: not applicable directly.

## References

- ADR-0029 (versioning, release and governance as code) — D7 phasing; this
  ADR adds the missing graduation half of that phasing.
- ADR-0030 (supply-chain security and SSDLC hardening) — several D-items
  cited here as still-advisory examples.
- ADR-0034 (unified OPA authorization) — Phase 5 enforcement, the concrete
  case that motivated this ADR: 14 money-path services still
  `authz.enforce: false`.
- `openbank-libs/governance/rules.yaml` — the file this ADR amends the
  schema of.
