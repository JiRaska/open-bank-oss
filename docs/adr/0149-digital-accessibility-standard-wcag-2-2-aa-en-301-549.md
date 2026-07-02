# ADR-0149 — Digital accessibility standard (WCAG 2.2 AA / EN 301 549)

Date: 2026-07-02
Decision-Status: Proposed   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

The European Accessibility Act (Directive (EU) 2019/882) has been
applicable to consumer banking services since 28 June 2025; retail banking
is explicitly in scope (Annex I). `openbank-admin-ui` has a mature,
CI-enforced quality-guard pattern (graceful-error states, bilingual
strings, app-shell rules, all backed by guard tests under `src/test/`), but
none of those guards check accessibility, and no ADR has decided a
conformance target for either the admin UI or, more importantly, the
customer-facing `openbank-app` (ADR-0064/0066/0073), which is the surface
actually in the EAA's regulatory scope — the admin UI is an internal
operator tool, not a consumer-facing service. Retrofitting accessibility
after a UI is built is materially more expensive than building to a
standard from the start (component selection, color-contrast tokens,
focus-order, and screen-reader semantics are easiest to get right at
initial construction), which is the argument for deciding this now rather
than after `openbank-app` reaches a wider release.

## Decision

We will adopt WCAG 2.2 level AA, aligned with EN 301 549, as the
conformance target for customer-facing surfaces (`openbank-app`, the PSD2
developer portal per ADR-0093), and as a best-effort (not gated) target for
the internal admin UI, given the latter is not in the EAA's regulatory
scope and the existing guard-test investment there is already high. For
`openbank-admin-ui`, we add one new guard test in the same family as the
existing graceful-state/i18n/shell guards: an `axe-core` automated check
run against key pages in CI, non-blocking initially (advisory, per
ADR-0144's graduation model, with a target enforce date) so it surfaces
regressions without immediately blocking every existing PR. For
`openbank-app`, the equivalent decision (specific tooling, since it is
Kotlin Multiplatform/Compose rather than React) is deferred to an ADR in
that repository, cross-referenced here per ADR-0147.

## Alternatives considered

- **WCAG 2.2 AAA.** Rejected — AAA includes success criteria (e.g. sign
  language interpretation, extended audio description) that are
  disproportionate for a banking control-plane/consumer-app pair at this
  stage; AA is the standard EN 301 549 itself references and is the
  de facto regulatory baseline.
- **Defer until after public launch / after `openbank-app` reaches GA.**
  Rejected — the EAA is already in force for the regulated surface
  (consumer banking), and retrofitting is the more expensive path; a
  reference implementation claiming banking-grade quality should not ship
  a launch-blocking compliance gap.
- **Apply the same enforced (blocking) gate to `openbank-admin-ui` as to
  `openbank-app`.** Rejected — the admin UI is an internal operator tool
  outside EAA scope; making its accessibility gate blocking today would add
  friction disproportionate to the actual regulatory requirement. Advisory
  with a stated target date (per ADR-0144) is the appropriate weight.

## Consequences

**Positive**
- Closes a genuine, previously undecided regulatory gap for the actual
  in-scope surface (`openbank-app`).
- Reuses the existing, proven CI-guard-test pattern in `openbank-admin-ui`
  rather than inventing a new enforcement mechanism.
- Deciding a target now, before `openbank-app` accretes more screens, is
  cheaper than a retrofit later.

**Negative**
- `axe-core` and similar automated tools catch a meaningful but incomplete
  subset of WCAG success criteria (color contrast, missing labels, ARIA
  misuse); full AA conformance additionally requires manual review
  (keyboard-only navigation, screen-reader walkthroughs) that this ADR does
  not itself schedule.
- The `openbank-app` decision is explicitly deferred to that repo, which
  means this ADR alone does not close the regulatory gap — it only commits
  to closing it and names the mechanism (ADR-0147 cross-reference).

**Neutral**
- Does not require replacing the admin UI's existing component choices
  (Radix UI is already a reasonably accessible-by-default headless
  component base).

## Compliance impact

- PCI DSS: not applicable.
- DORA: not applicable directly.
- GDPR: not applicable directly.
- PSD2: relevant to the developer portal (ADR-0093), which should meet the
  same target as other public-facing surfaces.
- CNB: not applicable directly. Additionally: European Accessibility Act
  (Directive (EU) 2019/882) and EN 301 549 — the primary regulations this
  ADR targets.

## References

- ADR-0064 (customer app Kotlin Multiplatform) — the primary in-scope
  surface; the `openbank-app`-side ADR is tracked via ADR-0147.
- ADR-0066 (passwordless customer authentication) — a customer-app flow
  where accessible design (e.g. biometric fallback affordances) matters
  directly.
- ADR-0093 (public developer portal for PSD2 XS2A) — the other externally
  reachable surface in scope.
- ADR-0076 (admin-ui integration and e2e testing) — the existing guard-test
  pattern this ADR extends.
- ADR-0144 (gate graduation) — governs the admin-ui `axe-core` check's path
  from advisory to enforced.
- ADR-0147 (cross-repo governance) — the mechanism for tracking the
  `openbank-app`-side accessibility ADR from this repo.
