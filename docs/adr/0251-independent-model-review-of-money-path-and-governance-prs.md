---
date: 2026-08-09
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, governance, ai-agents, compliance]
summary: "Restore independent model review, narrowed to money-path, security-type and governance-scope PRs (~16/day) and driven by the Claude CLI, with the proof-of-review check structurally unable to share a condition with the review itself."
---

# ADR-0251 — Independent model review of money-path and governance PRs

## Context

ADR-0154 introduced automated review of AI-authored PRs. It was retired on 2026-08-09
(#2161, #4281) after being measured as reviewing nothing: GitHub Models was withdrawn on
2026-07-30 and answers HTTP 410, and the Claude fallback carried `retired != 'true'` in its
own `if:` — so the standby was switched off in exactly the case it existed to cover. Across
10 consecutive runs the review step and the step named "Verify the Claude fallback actually
reviewed" were both `skipped`, and all 10 runs concluded `success`.

The retirement commit states that restoring independent review is a new decision. This is
that decision. `docs/compliance/finos-ccc-mapping.md` has carried the FINOS CCC control
"Independent review of AI-authored changes" as `gap — control withdrawn` since then.

Two facts about this repository shape what is buildable, both measured on 2026-08-09:

- **Volume.** 804 PRs merged in the preceding 7 days; 440 after excluding release-please,
  auto-deploy, admin-ui deploy and Dependabot. Reviewing all of them means ~63 review
  comments a day. A comment nobody reads is the same failure as a green control that checks
  nothing, only more expensive — so volume, not capability, is the binding constraint.
- **Credentials.** The `anthropics/claude-code-action` route is unavailable here: it fails
  with `Claude Code is not installed on this repository`, requiring an owner-only GitHub App
  install, and that check fires before any model call. The `claude` CLI needs no App. The
  repo secret `CLAUDE_CODE_OAUTH_TOKEN` was independently dead (`401 OAuth access token is
  invalid`, confirming #2161) until rotated on 2026-08-09; it now answers.

There is also a standing constraint from the platform owner: earlier automated review was
experienced as slowing the merge path. That is a design input regardless of what the
retirement record attributes the withdrawal to.

## Decision

We will run independent model review, **narrowed and non-blocking**, on the subset where an
extra reader is worth reading:

1. **Scope.** A PR whose conventional-commit scope is a `rules.yaml: money_path_services`
   service, whose TYPE is `security`, or whose SCOPE is `governance`. Measured over the same 7
   days that is 114 PRs — **16.3/day**, median diff 290 lines. Everything else is out of scope,
   by decision rather than by omission.

   `governance` is a scope and never a type: `rules.yaml: commits.types` is
   feat|fix|perf|refactor|docs|test|chore|build|ci|security, so a rule keyed on a
   `governance:` type is unreachable dead code of the same shape as the
   `principal.type == "SERVICE"` rego rule this repo already banned. The first draft of this
   ADR had exactly that defect and undercounted its own scope by half.

   The scope match also normalises the `-service` suffix, because `money_path_services` and
   real commit scopes disagree about it and both spellings are in live use for the same
   service (`fix(fraud)` 7x and `fix(fraud-service)` 1x in a single week). Matching the list
   literally covered 48 PRs instead of 114 and missed `fix(ledger)` outright — the most
   obviously in-scope shape there is. Both defects were found by running the rule against
   hand-written cases whose answer was known, not by reading it.
2. **Driver.** The `claude` CLI (`npm i -g @anthropic-ai/claude-code`, `claude -p`),
   authenticated with `CLAUDE_CODE_OAUTH_TOKEN`. No GitHub App dependency.
3. **Non-blocking, by construction.** Not a required status check; triggered once on
   `ready_for_review` rather than on every push; posts a comment, never a `REQUEST_CHANGES`
   review. `main-protection` has `required_approving_review_count: 0`, so no merge waits on
   it. The added wall-clock on the merge path is zero, not small.
4. **The proof-of-review check must not be able to share a condition with the review.**
   This is the specific defect that retired ADR-0154, so it is a structural requirement, not
   a coding note: the verification step runs `if: always()`, reads the model's **reply
   text**, and treats a missing or empty transcript as *no review happened*. It never reads
   the driver's exit code. During construction, five consecutive probe failures
   (`workflow_dispatch` needing the default branch; `Unsupported event type: push`; a
   missing `id-token: write`; the GitHub App gate; then the dead token) would every one of
   them have reported a PASS to a verifier reading an exit code.
5. **Silence must mean something.** The reviewer is instructed to emit findings or an
   explicit "no findings" line. A run that posts neither is a failure of the workflow, not a
   clean review.

## Alternatives considered

- **Review all 440 non-bot PRs.** Rejected on volume, not cost (~$13/month on Sonnet at that
  rate). 63 comments a day is a feed nobody reads, and an unread reviewer is indistinguishable
  from the withdrawn one while costing money and looking like coverage.
- **Repair ADR-0154's workflow in place.** Rejected: its primary is permanently retired
  rather than flaky, so the repair is a rewrite of every stage. Re-pointing it would also
  have inherited the shared-condition defect, which is the part actually worth not repeating.
- **Install the Claude Code GitHub App and keep `claude-code-action`.** Not rejected on
  merit — it is a live option requiring an owner-only install. Deferred because the CLI has
  fewer moving parts, and because the action exits 0 on its own recorded failure, which is
  the exact behaviour that made the previous control unfalsifiable.
- **Make review a required check.** Rejected outright. It would put a model in the merge
  path of a repository merging ~115 PRs a day, and the owner's stated objection to earlier
  automated review was precisely latency.
- **Do nothing and rely on human review.** Rejected as unbacked: `main-protection` has
  `required_approving_review_count: 0`, so "sensitive scopes defer to humans" defers to a
  ruleset that requires no human (#2183). Recording that gap is honest; leaving it as the
  whole answer is not.

## Consequences

**Positive**
- The FINOS CCC control "Independent review of AI-authored changes" gets a backing mechanism
  again, on the subset where the platform actually carries risk.
- A dead reviewer is now visible: the proof-of-review check cannot be disabled by the same
  condition that disables the review.
- Cost is ~$20/week at Opus tier for this volume — the narrow scope is what makes the more
  capable model affordable.

**Negative**
- ~86% of merged PRs get no model review. That is the decision, and it should be restated
  rather than quietly forgotten: this control does not cover the fleet.
- The OAuth token expires. When it does, this control stops — the proof-of-review check
  makes that loud rather than silent, but it does not prevent it.
- The scope is derived from commit scope and type, so a money-path change landing under an
  unrelated scope is not reviewed.

**Neutral**
- Two independent copies of `CLAUDE_CODE_OAUTH_TOKEN` exist (the repo secret and the local
  launchd env file). They rotate separately.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is processed by this control.
- DORA:    not applicable — this is an internal engineering control, not an ICT third-party
  or incident-reporting obligation. Named here only because the reviewed subset includes
  money-path services.
- GDPR:    not applicable — PR diffs are the platform's own source code; no personal data.
- PSD2:    not applicable — no payment-service functionality changes.
- CNB:     not applicable — no regulatory reporting changes.

FINOS CCC: this ADR is the backing decision for the control "Independent review of
AI-authored changes", recorded as `gap — control withdrawn` in
`docs/compliance/finos-ccc-mapping.md` since 2026-08-09. The mapping row is updated to
describe the narrowed scope, and must not claim fleet-wide coverage.

## References

- ADR-0154 (deprecated) — the withdrawn predecessor
- #2161 — the OAuth token rejected at the gateway; re-measured and rotated 2026-08-09
- #4281 — retirement of agent-review, with the 10-run measurement
- #2183 — `required_approving_review_count: 0`, so "defer to humans" defers to no one
- `openbank-libs/governance/rules.yaml: money_path_services` — the reviewed scope
- `docs/compliance/finos-ccc-mapping.md` — the control row this ADR backs
