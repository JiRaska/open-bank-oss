---
date: 2026-10-31
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [gitops, ci, supply-chain]
summary: "Image pins move out of main into a bot-owned deploy/sandbox branch read by multi-source ArgoCD Applications; manifests stay on main, so a deploy no longer merges a PR, never moves main and never waits on the merge train."
---

# ADR-0358 — Image pins live on a bot-owned deploy branch, not in main

## Context

ADR-0010 made ArgoCD the delivery mechanism and every Application tracks `main`
(`git grep -h 'targetRevision:' origin/main -- openbank-infra/gitops/apps openbank-infra/gitops/bootstrap`
prints 90 × `targetRevision: main`). A deploy is therefore a pull request into `main`:
`auto-deploy.yml` ("Open gitops PR", `base: main`) and `admin-ui-deploy.yml` ("Open GitOps PR
(auto-merge)", `base: main`) each rewrite an `image:` line under `openbank-infra/gitops/components/`
and open a bot PR.

ADR-0042 keeps `main-protection` strict (up-to-date branches) with no bypass actors, and ADR-0343
built the merge train around it. ADR-0343's own Consequences already name the cost: a deploy-bot PR
is a foreign merge that moves `main`. Every such merge leaves the strict-lane PR the train reserved
`main` for behind, and that PR re-runs the full fleet CI. The train therefore holds deploy PRs
behind reservations, and on top of that sit the deploy window (`deploy-window.py`), supersede churn
(`supersede-deploy-prs.sh`) and stale-pin verification. Measured 2026-10-30: an admin-ui change
merged at 16:41Z had not reached the cluster by 01:52Z the next day; three successive deploy PRs
(armed, green) were closed as superseded before any merged.

None of ADR-0010, 0042, 0044, 0236 or 0343 considers where the pin lives; ADR-0343's Alternatives
cover merge queue, strict on/off, serial rebase and `train/*` carriers, not a pin outside `main`.
This ADR is needed because the pin is the only thing a deploy changes, and it is the one thing
that has no reason to share `main`'s review and strict-CI contract — the image it names was
already built from a `main` commit that passed that contract.

## Decision

We will separate WHAT runs (manifests) from WHICH IMAGE runs (pins):

1. **Pins move to a bot-owned branch `deploy/sandbox`.** It holds one small kustomize image
   overlay per component (`pins/<component>/kustomization.yaml` with an `images:` entry carrying
   the tag and sha256 digest) and nothing else.
2. **Applications become multi-source.** Manifests keep coming from `main`; the image overlay
   comes from `deploy/sandbox`. A manifest change still goes through a reviewed PR into `main`.
3. **The build bot commits the pin directly** to `deploy/sandbox` after the same checks it runs
   today (can-i-deploy, digest present, `deploy_holds` honoured, source is an ancestor of
   `main`). Signed commit, GitHub App identity. No PR, no deploy window, no supersede: a newer
   pin simply overwrites an older one, and an older build can never overwrite a newer pin
   (ancestry check against the pin already on the branch).
4. **`deploy/sandbox` is protected** by a ruleset: only the deploy App may push, signed commits
   required, no force-push, no deletion.
5. **Rollback is a commit** on `deploy/sandbox` restoring the previous pin, by the same App via
   `workflow_dispatch`, so it stays audited and signed.

Migration is per Application, admin-ui first, so a regression is bounded to one component.

## Alternatives considered

- **ArgoCD Image Updater with git write-back** — same topology (pins on a branch), but the
  pinning decision moves into a third-party controller that knows nothing of can-i-deploy,
  `deploy_holds` or our signing; we would re-implement those as its plugins. Rejected: it moves a
  supply-chain control out of code we gate.
- **Exempt deploy PRs from strict up-to-date via a path-scoped ruleset** — conflicts with
  ADR-0042's no-bypass stance, and does not fix the cause: an exempt merge still moves `main` and
  still voids the reserved strict-lane CI.
- **A separate gitops repository** — cleanest isolation, but a second repo's rulesets, signing,
  gates and secrets, and 90 `repoURL` changes, for the same effect as one protected branch.
- **Keep deploy PRs, tune the lanes** (per-kind windows #14238, never closing an armed PR #14346)
  — kept as the interim fix; it makes the PR path reliable but cannot make it fast, because the
  merge still moves `main` (ADR-0343 rejected a disjoint fast lane during a reservation).

## Consequences

**Positive**
- A deploy is build → signed commit → ArgoCD sync: minutes, independent of the merge train,
  the strict lane and any other PR.
- `main` stops receiving dozens of bot commits a day; the merge train and strict lane lose
  their largest source of foreign merges.
- Deploy windows, supersede logic and their gates (`supersede-deploy-prs-ancestry`,
  `deploy-window-decision`) become obsolete and are deleted at the end of migration.

**Negative**
- Gates that read the deploy PR diff on `main` must be re-pointed at pushes to
  `deploy/sandbox`: `deploy-hold-image-pins` (`check-deploy-holds.py`),
  `check-deploy-source-freshness.py`, and `check-deploy-drift.py` (ADR-0236, which compares
  pins on `main` to `version.txt` and must read pins from the deploy branch).
- What is deployed is no longer readable from `main` alone; the admin-ui deployment views and
  any tooling that greps `main` for pins must read `deploy/sandbox`.
- Multi-source Applications are a newer ArgoCD feature; each migrated app must be verified
  synced and healthy before the next.

**Neutral**
- Admission controls are unaffected: Kyverno `verifyImages` and cosign attestations verify the
  image itself, wherever its tag was written.

**Enforcement.** A new enforced gate `deploy-pin-branch-contract` runs on every push to
`deploy/sandbox` and fails unless the commit touches only `pins/**`, every pin carries a digest,
no held service changes, and each pinned source sha is an ancestor of `main`. The ruleset in
Decision 4 is checked by the existing ruleset-parity tooling (ADR-0254).

### Delivery check

```
git grep -h 'targetRevision:' origin/main -- openbank-infra/gitops/apps | sort | uniq -c
```
Expected after delivery: every Application that pins an image lists a `deploy/sandbox` source;
`main` remains only for manifest sources. Live:
`kubectl -n argocd get applications -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.sources[*].targetRevision}{"\n"}{end}'`
shows both revisions for each migrated app, and `gh pr list --search "head:chore/gitops-auto-deploy-"`
returns nothing once migration is complete.

## Compliance impact

- PCI DSS: not applicable — no cardholder data path changes; the images deployed are the same.
- DORA: change management for deployments stays signed, attributable and reversible; the audit
  trail moves from PR history on `main` to signed commits on `deploy/sandbox`.
- GDPR: not applicable — no personal data processing changes.
- PSD2: not applicable — no API or SCA behaviour changes.
- CNB: not applicable — no reporting or prudential surface changes.

## References

- ADR-0010 (ArgoCD GitOps), ADR-0042 (branch protection), ADR-0236 (deployed==main drift),
  ADR-0254 (ruleset parity), ADR-0343 (merge train).
- `.github/workflows/auto-deploy.yml`, `.github/workflows/admin-ui-deploy.yml`,
  `openbank-infra/gitops/bootstrap/root-app.yaml`, `openbank-infra/gitops/apps/admin-ui.yaml`.
- Interim fixes: #14238, #14346.
