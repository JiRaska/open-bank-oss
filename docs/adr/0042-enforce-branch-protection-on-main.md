# Enforce branch protection on `main` (server-side governance gate)

Date: 2026-05-31
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiri Raska

> **Realization (2026-06-02, Phase 1 applied).** The `main-protection` ruleset is
> live on `main` (`enforcement: active`), applied via
> `openbank-infra/scripts/apply-branch-protection.sh` (governance-as-code, idempotent
> upsert). **Phase 1 was implemented as a repository ruleset, not classic branch
> protection** (a deliberate evolution from the "classic for Phase 1" text below): the
> ruleset can *additionally* require **signed commits** and **linear history** — both
> already the repo's practice (commits are GitHub-`Verified`; merges are squash-only) —
> and is the single artifact that also carries the Phase 2 per-path money-path logic.
> Active rules: `pull_request` (PR required, approvals = 0 to preserve the autonomous
> loop), `required_status_checks` (strict; `all-green` + `Validate manifests` + `Gitleaks`
> — the always-run, pool-reliable set; `all-green` is `if: always()` so it never deadlocks
> a docs/infra PR), `required_signatures`, `required_linear_history`, `non_fast_forward`,
> `deletion`, and `bypass_actors: []` (no admin bypass — this closes the PR #150 hole).
> `Trivy` stays out of the required set until host-CLI parity lands (ADR-0040 §3a). Phase 2
> (per-path money-path 2-approval ruleset) remains follow-up.

## Context

The `main` branch currently has **no branch protection** (`GET .../branches/main/protection`
returns `404 Branch not protected`). All three merge strategies are allowed and
`delete_branch_on_merge` is off. This means every governance rule about *how* code
reaches `main` — "no direct commits", "squash-merge via PR", "money-path needs 2
approvals + threat model" (`rules.yaml`, CLAUDE.md, ADR-0029/0030) — is enforced only
by **convention and the ship-auto skill**, not by the server.

The gap is not theoretical. PR #150 (a docs-only ADR) was squash-merged **immediately
while its CI checks were still `pending`**: `gh pr merge --auto` could not defer the
merge because GitHub only holds a PR for "merge when green" when a *required status
check* is configured — and none is. The change was harmless markdown, but the same hole
applies to any PR, including money-path code: a red or still-running pipeline does not
block the merge.

Forces at play:

- **Governance-as-code intent (ADR-0029).** Rules should be *derived from code and
  enforced in CI*, not maintained as etiquette. A rule that only the skill respects is
  one `gh pr merge` away from being bypassed.
- **ship-auto's guardrail depends on a gate that doesn't exist.** The skill says "merge
  only when green / stop before merge for money-path". Without a server-side required
  check and admin enforcement, the guardrail is advisory.
- **Path-scoped CI (ADR-0040) is a trap for required checks.** CI builds only changed
  modules, so path-conditional per-service checks (CodeQL, Dependency review, SBOM)
  reported `skipping` on the docs-only PR #150. Marking a path-conditional check as
  *required* would deadlock every PR that doesn't trigger it, so only checks that run on
  **every** PR regardless of path are candidates to require. **Caveat (empirical, PR
  #151):** the path-scoping is not as clean as #150 suggested — the *also* docs-only PR
  #151 unexpectedly triggered the full `build (openbank-*-service)` matrix, two of which
  (`audit-service`, `balance-service`) failed. So "this check never runs on a docs PR"
  cannot be assumed from one observation; the required-check set must be **validated
  against live behaviour across several PR shapes**, not derived a priori.
- **A required check presupposes pool-wide reliability — which Trivy currently lacks.**
  On PR #151 the `Trivy` job failed with `trivy: command not found` (exit 127) because
  the host-CLI binary (ADR-0040 §3a) is **not installed on every runner in the
  arch-mixed pool** (the arm64 Mac mini has `/opt/homebrew/bin/trivy`; the host that took
  the job did not). A check that is red purely from runner-hygiene drift must **not** be
  made required until host-CLI parity is guaranteed across the pool — otherwise the gate
  blocks merges on infrastructure state, not code quality.
- **Autonomy vs. human-gate tension.** We deliberately run an autonomous ship-auto loop
  for safe, non-money-path changes, gated by an *independent second-model* review. That
  Sonnet review is **not** a GitHub "approval". Requiring ≥1 human approval branch-wide
  would block the autonomous loop on every change — including the safe ones it is meant
  to handle.
- **Per-path approval counts.** `rules.yaml` wants 1 approval by default but **2** for
  `money_path_services`. Classic branch protection exposes a single branch-wide approval
  number; per-path differentiation needs the newer **repository rulesets**.
- **Admin bypass.** The repo operator is an admin; unless protection is set to enforce
  for admins, an admin `gh pr merge` slips past any rule — exactly what happened.

## Decision

**We will enforce governance on `main` server-side, in two phases: Phase 1 a classic
branch-protection rule that makes "PR-only, merge-only-when-green" a technical fact
without breaking the autonomous ship-auto loop; Phase 2 a per-path repository ruleset
that enforces the money-path 2-approval + threat-model rule.**

### Phase 1 — classic branch protection on `main` (do now)

1. **Require a pull request before merging.** No direct pushes to `main`.
2. **Require status checks to pass**, and only the **always-run, pool-reliable** ones.
   The *candidate* set is `Detect changed services`, `Gitleaks`, `Trivy`,
   `Validate manifests`; path-conditional checks (CodeQL, Dependency review, SBOM,
   Admin UI, per-service `build (openbank-*)`) are **deliberately not required** so
   docs/infra/back-end PRs do not deadlock. **Two preconditions before a check enters the
   required set (both surfaced by PR #151):** (a) it must be observed green across
   several PR shapes — `Trivy` is held *out* until the missing-binary runner-hygiene gap
   is fixed and it passes pool-wide; (b) the `build (openbank-*)` matrix is confirmed
   path-conditional in practice (PR #151 shows it can fire on a docs PR), so it stays
   non-required regardless. Net: apply Phase 1 initially with the **proven-green subset**
   (`Detect changed services`, `Gitleaks`, `Validate manifests`) and add `Trivy` once
   host-CLI parity lands.
3. **Require branches to be up to date before merging** (`strict: true`).
4. **Enforce for admins** (`enforce_admins: true`) — no silent admin bypass; this is the
   line that actually closes the #150 hole.
5. **Required approvals = 0.** Intentionally, so the autonomous ship-auto loop (Sonnet
   independent review + green CI) keeps working for safe, non-money-path changes. The
   CI gate, not a human approval, is the Phase-1 safety mechanism.
6. **Block force-pushes and deletions** of `main`.
7. **Enable `delete_branch_on_merge`** so merged PR branches are cleaned up.

### Phase 2 — repository ruleset for money-path (follow-up)

8. A **path-scoped ruleset** over `money_path_services` globs (e.g.
   `openbank-ledger-service/**`, `openbank-sepa-*/**`, …) that requires **2 approvals +
   CODEOWNERS review**, and (where checkable) presence of the
   `docs/threat-models/<service>.md` file (ADR-0030). This makes the `rules.yaml:
   money_path_approvals = 2` rule a *server-enforced* fact rather than a convention, and
   is precisely where ship-auto already STOPs before merge.

The required-check **context names must be confirmed against a recent run** before
applying; they are taken from the job names observed on PR #150.

## Alternatives considered

- **Do nothing — keep convention + ship-auto only.** Zero setup. **Rejected:** leaves the
  observed "merge-while-red" hole open for *all* PRs including money-path; contradicts
  ADR-0029's "enforce in CI, don't rely on etiquette".

- **Require ≥1 approval branch-wide.** Strongest human gate. **Rejected for Phase 1:**
  breaks the autonomous ship-auto loop on every change (the independent Sonnet review is
  not a GitHub approval), defeating a capability we deliberately built. Per-path
  approvals (Phase 2 ruleset) target the place approvals actually matter — money-path —
  without taxing safe changes.

- **Require all CI checks, including path-scoped ones.** Maximal coverage. **Rejected:** a
  required check that skips on a given PR never reports success, so it permanently blocks
  every docs/infra/back-end-only PR — a self-inflicted deadlock against ADR-0040's
  path-scoped design.

- **Rulesets only, skip classic protection.** Rulesets are the newer model and could
  express everything. **Partially adopted (Phase 2):** classic branch protection is used
  for the simple branch-wide gate (Phase 1) because it is well-understood and quick;
  rulesets are reserved for the per-path money-path logic they uniquely enable. Revisit
  consolidating both into rulesets later.

## Consequences

**Positive**
- **"Merge only when green" becomes a server fact**, not skill etiquette — the #150 hole
  is closed, including against admin merges.
- **Autonomous ship-auto loop preserved** for safe changes (approvals = 0 in Phase 1).
- **Money-path 2-approval rule gains server enforcement** (Phase 2), aligning the repo
  with `rules.yaml` and ADR-0030 instead of trusting process.
- **Cleaner repo hygiene** (no direct pushes, no force-push/deletion of `main`, branches
  auto-deleted).

**Negative**
- **Required-check list is maintenance surface.** If a workflow/job is renamed, the
  required context must be updated or `main` jams. Keep the list minimal (4 checks).
- **`strict: true` (up-to-date) can force rebases** on a busy `main`, adding churn for a
  small team — acceptable at current volume, revisit if it bottlenecks.
- **Phase 2 ruleset adds config** that must itself be reviewed and version-aware as the
  money-path service list evolves.

**Neutral**
- Approvals = 0 is a deliberate Phase-1 stance, not a permanent one; Phase 2 introduces
  approvals exactly where risk concentrates.
- Admin enforcement removes the operator's ability to fast-merge in an emergency; a
  documented break-glass (temporary protection toggle) covers genuine incidents and ties
  into the runtime-control-plane break-glass thinking (ADR-0033).

## Compliance impact

- PCI DSS: not applicable (no cardholder data in the merge gate).
- DORA: **applicable** — change control is an ICT-risk control; a server-enforced
  PR-only, CI-gated path to `main` with admin enforcement is exactly the auditable
  change-management evidence DORA expects, and removes a bypass.
- GDPR: not applicable.
- PSD2: **applicable (Phase 2)** — money-path services include payment/SCA/consent flows;
  server-enforced 2-approval + threat-model review strengthens segregation-of-duties on
  payment-impacting change.
- CNB: not applicable.

## References

- ADR-0029 — versioning, release and governance as code (enforce-in-CI principle).
- ADR-0030 — supply-chain security / SSDLC (money-path threat models, 2 approvals).
- ADR-0040 — CI execution model (path-scoped builds — why not all checks can be required).
- ADR-0033 — runtime control plane (break-glass framing for admin-enforcement override).
- `openbank-libs/governance/rules.yaml` — `money_path_services`, `review.money_path_approvals`.
- `.claude/skills/ship-auto` — the loop whose "merge when green / stop for money-path"
  guardrail this ADR makes server-enforced.
- Incident: PR #150 squash-merged while CI checks were `pending` (no required check on `main`).
- Incident: PR #151 (this ADR) — `Trivy` failed `command not found` (runner host-CLI parity
  gap) and a docs-only PR triggered the per-service `build` matrix with two failures; the
  evidence that reshaped the required-check set above.
