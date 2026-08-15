---
date: 2026-07-06
decision-status: deprecated
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, ci, governance]
summary: "Run an Agent review workflow that submits a real approve or request-changes review from a different model family (GitHub Models), while always deferring sensitive scopes such as money-path, governance and CI paths to humans."
---

# 154. Independent agent PR approval for non-sensitive changes

> **Withdrawn 2026-08-09 (#2161).** This decision shipped and then stopped being executable, with
> nothing going red. GitHub Models was retired on 2026-07-30 and answers every inference call with
> HTTP 410; that sets `ghmodels_retired=true`, and the Claude fallback carried `retired != 'true'`
> in its own `if:` — so the fallback was switched off in exactly the case it existed to cover.
> Measured over the last 10 runs from step conclusions: the review step and the step named "Verify
> the Claude fallback actually reviewed" were both `skipped` 10/10, and all 10 runs concluded
> `success`.
>
> The workflow is removed rather than repaired. Re-minting the credential would have produced a
> valid secret feeding a step that never runs — a green control that reviews nothing, which is
> worse than none because it reads as coverage. Restoring independent review is a new decision, and
> it depends on #2183: this ADR's "sensitive scopes always deferred to humans" clause defers to a
> ruleset whose `required_approving_review_count` is 0.


## Context

`main-protection` requires 1 approving review before a PR can merge (`allowed_merge_methods:
squash`, `required_approving_review_count: 1`). GitHub blocks self-approval — an author cannot
approve their own PR — so an agent-authored PR (increasingly common as Claude agents open
routine `chore`/`ci`/`fix` PRs in this repo) has no path to merge except a human clicking
Approve, or a repo admin bypassing the required-review rule outright.

The admin bypass is the wrong steady state: it is all-or-nothing (it also bypasses the review
requirement on a PR that genuinely needed scrutiny) and it silently trains agents to reach for
it as the default unblocking move, rather than treating "no reviewer available" as a signal to
slow down. What we actually want is real independent judgment on the boring 90% of PRs, and an
unconditional human gate on the sensitive 10%.

## Decision

We will run an **`Agent review`** GitHub Actions workflow
(`.github/workflows/agent-review.yml`, ADR-0153-adjacent issue #343, PR #344) that submits a
real `pull_request` review — `APPROVE` or `REQUEST_CHANGES` — as `github-actions[bot]`, subject
to two hard constraints:

1. **Independence, not rubber-stamping.** The primary reviewer runs on **GitHub Models**
   (`openai/gpt-4o` — free on this public repo) with an explicitly adversarial prompt ("find a
   reason to REJECT"), deliberately a *different model family* from the Claude agents that
   author most PRs here. A same-model reviewer approving its own reasoning is not a review.
   Fallback is Claude via `claude-code-action`, authenticated with a **subscription OAuth
   token** (`CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token`) rather than a paid API key —
   used only if GitHub Models errors or rate-limits.
2. **Scope guard — sensitive changes are always deferred to a human,** classified dynamically,
   never hand-maintained as a static list of PRs:
   - any file under a **money-path service** directory (sourced live from
     `rules.yaml: money_path_services` — never hardcoded);
   - `openbank-libs/governance/**` (the rules the whole gate is built on);
   - `.github/**` (CI/automation — including this workflow's own definition);
   - `release-please-config.json` / `.release-please-manifest.json` (release wiring);
   - `docs/adr/**` (architecture decisions — including this one);
   - `docs/threat-models/**`;
   - any `*.sql` / `*/db/migration/*` path.

   On a sensitive-scope PR the job posts a comment explaining why it deferred and submits **no
   review at all** — it never approves, never requests changes. Money-path PRs keep the existing
   **2 human approvals + threat model** requirement (ADR-0030) untouched; this workflow cannot
   satisfy that gate by itself even if it wanted to (it doesn't submit a second approval, and one
   bot review can't count twice).
3. **Non-blocking by construction.** `Agent review` is not a required status check. A model
   outage, a fork PR (read-only `GITHUB_TOKEN`, no secrets), or an unrecognized file pattern
   simply means no review gets posted — a human reviews exactly as before. It can only ever
   *add* signal, never remove the fallback path.

## Alternatives considered

- **Keep the admin bypass as the standard unblocking move.** Rejected — no independent check
  at all, and normalizes overriding branch protection as routine rather than exceptional.
- **Same-model reviewer (Claude reviewing Claude-authored PRs).** Rejected — not independent;
  a model is the worst-positioned reviewer of its own reasoning, and this would formalize
  rubber-stamping under the appearance of review.
- **Lower `required_approving_review_count` to 0 for `chore`/`ci` PR types.** Rejected — removes
  the review gate entirely rather than delegating it; no adversarial check would run at all.
- **Static allowlist of "safe" file paths reviewed and approved manually once.** Rejected — same
  failure mode as every other hand-maintained allowlist in this repo (see the `.gitleaks.toml`
  stale-path incident, issue #341): it silently drifts from reality as directories move. The
  money-path list is instead read live from `rules.yaml` at review time.

## Consequences

**Positive**
- Routine PRs (docs typos, gitops tag bumps, small `chore`/`ci` changes) get a genuine
  adversarial second opinion and can merge without a human click or an admin override.
- The adversarial framing has already caught a real (if ultimately unfounded) concern in
  practice — flagging PR #347's gitops image bump for registry verification, which a human then
  independently checked against ECR before overriding.
- Sensitive classes of change are *more* consistently gated than before: the scope guard is
  enforced by every PR, not by remembering to ask "should this need extra eyes?" case by case.

**Negative**
- The scope guard is a file-path heuristic and can have gaps. One is already known: a gitops
  manifest change under `openbank-infra/gitops/components/<money-path-service>/` is **not**
  currently caught by the money-path regex (which matches `^(service-dir)/`, i.e. source-tree
  paths, not the parallel gitops directory tree) — deploying an already-merged, already-reviewed
  artifact is lower-risk than an unreviewed code change, but the gap is real and is tracked as a
  follow-up rather than fixed here.
- An LLM reviewer can be wrong in either direction — a false `REQUEST_CHANGES` costs a human a
  dismissal-with-rationale (as done manually on PR #347); a false `APPROVE` on an under-scoped
  path is the more dangerous failure mode and is exactly why the scope guard exists and is
  deliberately conservative (defer-by-default on any pattern match).
- Depends on two external services (GitHub Models, Anthropic) being reachable from Actions;
  degrades gracefully (no review posted) rather than blocking, per the non-blocking design goal.

**Neutral**
- Does not change the required-approval *count* (still 1) or touch the money-path 2-approval
  rule; it only supplies who is allowed to provide routine-path approval.

## Compliance impact

- PCI DSS: not applicable — no cardholder data path touched; money-path services are explicitly
  excluded from this workflow's approval authority and retain their existing 2-human-approval +
  threat-model gate (ADR-0030).
- DORA: neutral-to-positive — narrows unreviewed-change risk (replaces ad hoc admin bypass with
  a documented, scope-limited automated check) without weakening the ICT change-management
  control for anything in scope of the DORA-relevant services.
- GDPR: not applicable.
- PSD2: not applicable.
- CNB: not applicable.

## References

- Issue #343 (proposal + follow-up tracking, now closed)
- PR #344 (`.github/workflows/agent-review.yml`)
- PR #347 (first real-world adversarial catch: gitops image-digest concern, dismissed after
  independent ECR verification)
- ADR-0030 (money-path 2-approval + threat-model requirement, left untouched by this decision)
- ADR-0029 (governance-as-code; `rules.yaml` as the single source of truth this workflow reads
  `money_path_services` from)
- Issue #341 / `.gitleaks.toml` stale-path incident — the precedent for why this ADR insists on
  a dynamically-sourced money-path list rather than a hand-maintained one
