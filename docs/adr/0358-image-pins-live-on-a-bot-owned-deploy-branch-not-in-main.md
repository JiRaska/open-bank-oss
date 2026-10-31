---
date: 2026-10-31
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [gitops, ci, supply-chain]
summary: "Image pins move to a bot-owned deploy/sandbox branch as a verified copy of main's manifests plus a kustomize image override; a deploy no longer merges a PR, never moves main and never waits on the merge train."
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

1. **Pins move to a bot-owned branch `deploy/sandbox`.** It holds one directory per component,
   `pins/<component>/`, and nothing else: `manifests/` is a verbatim copy of
   `openbank-infra/gitops/components/<component>/` at a `main` commit, `kustomization.yaml`
   lists those files and overrides the image (`images:` with tag and sha256 digest), and
   `pin.json` records `manifest_sha`, `image_source_sha`, image, tag, digest and a rollback flag.
2. **Applications read the pin as a single kustomize source.** `targetRevision: deploy/sandbox`,
   `path: pins/<component>`, same `repoURL` (so the existing repository credential is reused).
   Manifests are still authored and reviewed as PRs into `main`; the pin only ever carries a
   copy of a `main` commit, which the contract below proves byte for byte. A push to `main`
   that touches `components/<component>/` re-pins the manifests automatically (image kept),
   so a manifest change never waits for a person or for the next image build.
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

- **Multi-source Application: manifests from `main`, an `images:` overlay from
  `deploy/sandbox`** (this ADR's first draft) — does not render. Argo CD (v3.5 here) builds each
  source on its own, and a kustomize `images:` transformer rewrites only resources inside its own
  kustomization; `ref:` sources feed only Helm `valueFiles`. The overlay would emit nothing and
  the image would stay whatever `main` says.
- **Kustomize remote base pinned to a `main` sha** (`github.com/...//components/<c>?ref=<sha>`
  plus `images:`) — renders, but the repo-server clones the monorepo for every render (a local
  fetch hit kustomize's 27 s timeout), outside Argo's repository credential; a floating `?ref=main`
  would additionally be cached by the overlay's revision, so manifest changes would not sync.
  The verbatim copy has the same semantics (manifests pinned to a `main` sha) without either cost.
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

**Enforcement.** The contract (`check-deploy-pin-branch.py`): a commit touches only `pins/**`;
every pin carries a digest and its kustomization agrees with `pin.json`; `manifests/` equals the
component directory at `manifest_sha` file for file; `manifest_sha` and `image_source_sha` are
ancestors of `main`; neither moves backwards against the previous pin (a rollback may move only
the image); and a service under `deploy_holds` keeps its image. A push to `deploy/sandbox` runs
no workflow (the branch holds none), so the contract is checked by the writer on the candidate
before every write, and by the enforced gate `deploy-pin-branch-contract` against the live tip on
every CI run. The ruleset in
Decision 4 is checked by the existing ruleset-parity tooling (ADR-0254).

### Delivery check

```
git grep -h 'targetRevision:' origin/main -- openbank-infra/gitops/apps | sort | uniq -c
```
Expected after delivery: every Application that pins an image reads `deploy/sandbox`
(`path: pins/<component>`); `main` remains only for Applications without a bot-pinned image. Live:
`kubectl -n argocd get applications -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.source.targetRevision}{"\n"}{end}'`
shows `deploy/sandbox` for each migrated app, and `gh pr list --search "head:chore/gitops-auto-deploy-"`
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
