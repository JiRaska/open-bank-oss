---
date: 2026-10-31
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, testing]
summary: "Admin UI visual regression uses Playwright toHaveScreenshot with baselines committed to git and rendered only in one pinned Playwright container image; advisory until 10 consecutive runs show zero flake."
followup: "none — required-mode flip follows the 10-run flake measurement recorded in the PR that ships this lane"
---

# ADR-0359 — Visual regression lane with committed container-rendered baselines

## Context

ADR-0076 decided the Admin UI test layers (Vitest, MSW route tests, Playwright E2E with
`page.route()` mocks). It did not decide visual regression, and its E2E layer asserts DOM
and accessibility, never pixels. Test Intelligence already models an evidence kind `visual`
(`openbank-admin-ui/src/lib/types/test-intelligence.ts`), but no producer existed:
`collect-test-run-evidence.py` did not even accept the kind, so the column read zero for
every component. The admin-ui guide records why that matters: CSS and layout regressions
pass `tsc`, eslint and the jsdom render-smoke suite (PR #2556 shipped three).

Two things make this a decision rather than a task: where baselines live (they are the
oracle, and this repository is public), and which environment is allowed to render them
(a screenshot comparison is only as deterministic as its renderer).

## Decision

We will run a visual lane for `openbank-admin-ui`:

- **Tool:** Playwright `toHaveScreenshot`, the runner the Admin UI E2E suite already uses,
  with a separate `playwright.visual.config.ts` and specs under `e2e-visual/`.
- **Baselines:** PNGs committed under `openbank-admin-ui/e2e-visual/__screenshots__/`,
  reviewed in the PR diff like any other change.
- **Renderer:** exactly one, the `mcr.microsoft.com/playwright` image pinned by its
  linux/amd64 digest, invoked through `scripts/visual.sh` in CI and on laptops alike. The
  snapshot path carries no platform suffix because there is only one platform.
- **Determinism:** every browser `/api/**` call is served from fixtures (an unmocked call gets
  one fixed 404), `Date` is frozen, theme and language are set before first paint, motion is
  disabled, viewport 1440×900 at scale 1, `maxDiffPixels: 0`, and **no retries**.
- **Evidence:** the lane's JUnit (kept outside `build/test-results` so it is not double
  counted as e2e) becomes one `visual` specialized-evidence row in the Admin UI run
  envelope; zero executed comparisons or an absent report is `not-run`, never a pass.
  Failed comparisons upload actual/expected/diff images as a retained artifact.
- **Mode:** advisory (`continue-on-error`) until ten consecutive runs show no flake; then
  the `continue-on-error` line is removed and the step is merge-blocking through the
  existing `ui-build` → `ui` → `Validate manifests` chain.

## Alternatives considered

- **Hosted visual service (Chromatic, Percy, Applitools).** Hosted review UI and baseline
  storage. Rejected: sends rendered operator screens to a third party (even with fixture
  data the pages show internal topology), needs a secret that fork PRs cannot use, adds a
  paid dependency, and its verdict lives outside the repository's own evidence chain.
- **Self-hosted image diff (pixelmatch or odiff, baselines in object storage).** Keeps the
  repo small. Rejected: rebuilds what Playwright already ships, and baselines outside git
  lose the property that a baseline change is reviewed in the same diff as the code that
  caused it.
- **`toHaveScreenshot` on the bare `ubuntu-latest` runner.** No container. Rejected: the
  runner image's fonts and Chromium build drift with GitHub's image rollouts, and a
  developer on macOS cannot reproduce it, so every update would need a CI round trip and
  every runner-image bump would read as a regression.

## Consequences

**Positive**
- Test Intelligence receives real `visual` evidence for `openbank-admin-ui`.
- A pure CSS regression now fails a check; the PR carries the diff images.

**Negative**
- Binary baselines grow the repository (about 100–300 KB per page and theme).
- Any intended visual change must regenerate baselines via `scripts/visual.sh --update`.
- On Apple silicon the amd64 image runs emulated, so a local update is slow.

**Neutral**
- Gate: the lane is a step in `ci.yml`'s `ui-build` job, advisory by `continue-on-error`,
  not a `gates.yaml` entry — it is a test suite, like the E2E step beside it.

### Delivery check

`grep -c continue-on-error` on the `Visual regression (Playwright baselines)` step in
`.github/workflows/ci.yml` should read 0 once required; the Admin UI run envelope
(`test-intelligence-openbank-admin-ui` artifact, `run.json`) must contain a
`specializedEvidence` row with `"kind": "visual"`.

## Compliance impact

- PCI DSS: not applicable — test tooling for an operator UI, no cardholder data.
- DORA:    not applicable — no change to ICT operations or incident handling.
- GDPR:    not applicable — fixtures contain no personal data.
- PSD2:    not applicable — no payment or authentication flow change.
- CNB:     not applicable — no regulated reporting change.

## References

- ADR-0076 — Admin UI integration and E2E testing strategy
- `openbank-admin-ui/playwright.visual.config.ts`, `openbank-admin-ui/scripts/visual.sh`
