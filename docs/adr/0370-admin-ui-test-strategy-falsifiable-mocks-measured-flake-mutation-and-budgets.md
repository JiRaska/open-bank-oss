---
date: 2026-11-11
decision-status: proposed
delivery-status: partial
followup: "#15685 — U1–U6 are built (U5 mutation and U6 perf runs advisory until baselined); U7–U8 are not"
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, testing, api-contract]
summary: "Admin UI tests must be falsifiable: browser mocks validate against the served openapi.yaml, flake is measured and quarantined, mutation and per-module coverage ratchet decision logic, and perf, version skew and engines get budgets."
---

# ADR-0370 — Admin UI test strategy: falsifiable mocks, measured flake, mutation and budgets

## Context

ADR-0076 decided the Admin UI test layers (Vitest, a network-mock layer, Playwright E2E, a
docs schema guard). ADR-0359 added a visual lane, ADR-0149 an axe guard (route coverage is
now enforced by gate `admin-ui-a11y-route-coverage`), ADR-0273 the evidence snapshot. This
ADR is needed because those layers answer "do tests exist and run", not "can a test fail
when the product is wrong". Measured on `origin/main`, 2026-11-11:

- 760 Vitest files and 153 Playwright specs; `git grep -l 'route(' -- openbank-admin-ui/e2e`
  lists 130 files that answer BFF calls from hand-written `page.route()` bodies. Nothing
  checks those bodies against any service's `openapi.yaml`, so a mock may return a shape the
  service never sends and the spec stays green. ADR-0076 named this "Gap 4 — no contract
  guard between admin UI and service APIs" and chose MSW handlers as the living
  specification; `git grep -l "from 'msw'" -- openbank-admin-ui` returns 0 files today.
- `vitest.config.ts` thresholds are global: statements 36, branches 18, functions 29,
  lines 38. A global floor lets approval or money-formatting logic lose coverage while
  unrelated pages gain it.
- Playwright runs `retries: 1` with `includeRetries: true` JUnit, but nothing reads the
  pass-on-retry count; a retry silently absorbs instability. There is no quarantine.
- One browser project (`Desktop Chrome`). No property tests (`fast-check` absent), no
  mutation testing, no bundle or Web-Vitals budget, and no test of the UI against the
  previous released API contract of a service.

ADR-0076 explicitly scoped out mutation and performance testing ("UI logic is predominantly
display/routing"). That premise no longer holds for the parts this ADR targets: the UI now
carries four-eyes approval flows, money formatting and close-cycle consoles (ADR-0230,
ADR-0078). This ADR narrows, not reverses, that non-goal: mutation applies only to
`src/lib` decision logic, never to page rendering.

The companion mobile strategy (customer app repo, APP-0004) made the same move — every
item ships with proof that its gate fails on an injected defect. This ADR adopts that rule.

## Decision

We will make every Admin UI test layer falsifiable, delivered as eight independent PRs in
this order. Each PR's "Done" includes a committed negative case: the defect injected, the
gate run, the red result.

1. **U1 — Contract-validated browser mocks.** A Playwright fixture wraps `page.route()` and
   validates each fulfilled JSON body against the response schema of the matching operation
   in the target service's `openapi.yaml` (path and method resolved through the BFF proxy
   map). An unmatched operation or a schema violation fails the spec. Done: a mock adding an
   undeclared required field, or dropping a required one, turns red.
2. **U2 — Measured flake and quarantine.** A collector reads the Playwright JUnit
   `includeRetries` output; any test that passed only on retry is recorded in Test
   Intelligence (ADR-0273). A test over a pass-on-retry threshold moves to a committed
   quarantine list with an owner and expiry; quarantined tests run but do not gate, and an
   expired entry fails CI. Done: a deliberately order-dependent test is detected and listed.
3. **U3 — Per-module coverage ratchet.** Replace the global floor with per-directory floors
   for `src/lib/**` decision modules (approval, money, close, authz helpers), ratchet-only
   as ADR-0020 does for Kotlin. Done: a PR that deletes tests for one module fails while
   global coverage stays above the old floor.
4. **U4 — Property tests.** `fast-check` for formatting and parsing (amounts, IBAN, dates,
   filters, pagination cursors). Done: an off-by-one rounding in amount formatting is found.
5. **U5 — Mutation testing on decision logic.** StrykerJS, incremental, scoped to the U3
   modules, with a mutation-score ratchet; PR runs mutate changed files only. Done: a
   removed guard condition that existing tests miss is reported as a surviving mutant.
6. **U6 — Performance budget.** Per-route JS bundle size from the production build plus
   LCP/INP measured by Playwright on the stubbed server, advisory until ten stable runs
   (the ADR-0359 graduation rule), then enforced. Done: a 200 kB dependency on one route
   breaches the budget.
7. **U7 — Version skew.** Run U1's validation a second time against each service's
   `openapi.yaml` at its latest release tag, so the UI cannot require a field the deployed
   service version does not yet serve. Done: a UI read of a field added only on `main`
   fails the skew run.
8. **U8 — Engine coverage.** WebKit and Firefox projects in a nightly run (not per PR),
   failures filed as issues. Done: a Chromium-only API used by a page fails nightly.

Axe accessibility is not in this list: route coverage is already enforced, and ADR-0149
keeps admin-UI violations non-blocking.

CI budget: U1–U5 together may add at most +4 min to the Admin UI PR job, measured against a
baseline taken with a pinned `gh run list --workflow _service-ci-pr.yml` query recorded in
the U1 PR. U6–U8 run outside the PR critical path until measured.

## Alternatives considered

- **Adopt MSW as ADR-0076 prescribed, and validate handlers instead of routes.** Pros: one
  mock definition for Vitest and Playwright. Cons: 130 spec files would be rewritten before
  any validation exists, and MSW handlers are just as hand-written. Rejected for now:
  validating at the `page.route()` seam gives the guarantee without the rewrite; a later MSW
  migration can reuse the same validator.
- **Pact consumer contracts from the Admin UI.** Pros: provider replay catches wrong paths.
  Cons: one pact per service for ~30 BFF upstreams, plus broker wiring. Rejected as the
  first step; schema validation covers shape for every route at once, and a pact is added
  where a money-path path mismatch is the risk.
- **Raise the global coverage floor.** Rejected: coverage of display code would satisfy it
  while decision logic rots, which is the defect this ADR exists to stop.
- **Do nothing beyond ADR-0076.** Rejected: green suites against unvalidated mocks are the
  false-pass class the repo has repeatedly paid for (pacts without replay, mocked
  repositories).

## Consequences

**Positive**
- A green Admin UI E2E run means the mocked data is a shape the service declares.
- Flake becomes a tracked number with owners instead of a hidden retry.
- Decision logic carries a mutation score, so coverage cannot be bought with assertion-free
  tests.

**Negative**
- Existing mocks that violate their schema will surface at once; U1 lands with a baseline
  of known violations that may only shrink.
- Mutation and multi-engine runs cost CI minutes; bounded above.

**Neutral**
- Gates: U1 `admin-ui-mock-contract`, U2 `admin-ui-flake-quarantine`, U3
  `admin-ui-module-coverage-ratchet`, U5 `admin-ui-mutation-ratchet` — each declared in
  `.github/gates/gates.yaml` with a self-test that must fail on its negative fixture; U6
  starts advisory with a `target_enforce_date` per ADR-0144.

### Delivery check

```bash
for g in admin-ui-mock-contract admin-ui-flake-quarantine \
         admin-ui-module-coverage-ratchet admin-ui-mutation-ratchet; do
  grep -A3 "id: $g" .github/gates/gates.yaml | grep -q 'mode: enforced' || echo "MISSING $g"
done
git grep -l "from 'fast-check'" -- openbank-admin-ui/src/test | wc -l   # expect >= 1
grep -c "name: '\(webkit\|firefox\)'" openbank-admin-ui/playwright*.config.ts
```

Expected: no `MISSING` lines, a non-zero `fast-check` count, and two engine projects.

## Compliance impact

- PCI DSS: not applicable — test tooling for an operator UI, no cardholder data.
- DORA:    not applicable — no change to ICT operations or incident handling.
- GDPR:    not applicable — fixtures contain no personal data.
- PSD2:    not applicable — no payment or authentication flow change.
- CNB:     not applicable — no regulated reporting change.

## References

- ADR-0076 — Admin UI integration and E2E testing strategy (Gap 4; mutation/perf non-goals)
- ADR-0020 — Kover per-module regression floor
- ADR-0063 — pitest mutation testing on money-path math
- ADR-0144 — gate graduation deadlines
- ADR-0149 — accessibility standard; admin UI axe guard stays non-blocking
- ADR-0273 — unified test intelligence evidence
- ADR-0359 — visual regression lane and its ten-run graduation rule
- `openbank-admin-ui/playwright.config.ts`, `openbank-admin-ui/vitest.config.ts`
