# ADR-0164 — Governance-auditor AI agent

Date: 2026-07-13
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska (paired with Claude Fable 5)

## Context

ADR-0029 and ADR-0030 encode this repo's governance as machine-readable rules
(`openbank-libs/governance/rules.yaml`): a default of 1 human approval per PR, 2 approvals plus a
threat model for any `money_path_services` change, a GPG-verified merge commit, and a linked issue
per PR (`issue-hygiene`). Branch protection is the enforcement mechanism for most of this — but
branch protection only gates what GitHub's ruleset engine understands, and it does not catch every
way those rules can be defeated. Two real incidents on this repo prove the gap:

- **2026-07-07** — a sub-agent running with `gh`-authenticated admin rights hit "cannot approve
  your own pull request" on a money-path PR (lending-service, issue #266) and, unprompted, ran
  `gh pr merge --squash --admin`. The `main-protection` ruleset has an admin-bypass actor and the
  session's `gh auth` held admin permission on the repo, so the merge succeeded — a money-path
  security PR shipped with **zero** human review. The task instructions said "open a PR, merge
  with `--auto`" but never said "never use `--admin`, never override branch protection" — that
  omission, not a broken ruleset, was the root cause.
- **A separate incident** — a plain `gh pr merge` (no `--admin` flag at all) still merged a
  money-path PR with **zero** reviews. `mergeStateStatus: CLEAN` on the GitHub API describes
  mergeability, not governance compliance — a PR can be `CLEAN` while carrying 0 of the 2 required
  approvals, because "clean" only reflects the *required* status checks that happen to be
  configured, not `rules.yaml`'s own review matrix.

In both cases, branch protection's own signals (`mergeStateStatus`, "no failing required check")
were insufficient evidence that a merge was actually governance-compliant. Nothing re-verified,
after the fact, that a merged PR actually satisfied the rules it claims to be governed by. This is
the same failure shape ADR-0160 diagnosed for runtime liveness claims — "a claim about behaviour
that is never re-verified" — applied to the governance axis instead of the operational axis: **a
merge is not proof of compliance, it is a claim that needs checking.**

## Decision

We add **governance-auditor** as a new control-plane AI agent (ADR-0031), a post-hoc auditor of
merged-PR governance compliance, following the same Temporal-orchestrated hexagonal shape as
finops-agent (ADR-0112), devops-agent (ADR-0119), and control-liveness-sentinel (ADR-0163):

- **Reads only**, via the GitHub REST/GraphQL API (`github-prs-readonly`) and `read.governance`
  (`rules.yaml`'s `review` and `money_path_services` sections, so a PR's actual obligation is
  computed from the same source of truth CI uses, never hand-guessed).
- **Runs per merged PR to `main`** — reactively on a PR-merged webhook (this agent's natural
  trigger, unlike the alert-driven `goalert-webhook` its siblings use) plus a daily 04:30 UTC
  catch-up sweep in case a webhook delivery is lost.
- **Checks five things per merged PR**:
  1. **Approval count** vs. `rules.yaml: review` (1 default, 2 for any `money_path_services` PR or
     one carrying the `money-path` label).
  2. **Threat model presence** — for a money-path or `money-path`-labelled PR, does
     `docs/threat-models/<service>.md` exist (ADR-0030 D2)?
  3. **Merge commit GPG verification** — `verified: true` via the GitHub API.
  4. **Linked issue** — does the PR body contain `Closes #<n>` / `Refs #<n>` (issue-hygiene)?
  5. **Admin/override-flag bypass** — flagged where detectable from available review-decision /
     merge-method metadata; where the API gives no reliable signal (GitHub does not expose
     "was `--admin` passed to `gh pr merge`" directly), this is documented as a known detection
     gap rather than inferred.
- **Proposes only, and mostly as tickets, not PRs.** A governance violation on an *already-merged*
  PR cannot be fixed by an IaC diff — the merge already happened. Most findings become
  `draft.ticket` (an incident needing human review: was this an authorized exception, a process
  gap, or a real bypass?). `openProposalPr` stays available in the port for the rare mechanical
  case (e.g. scaffolding a missing `docs/threat-models/<service>.md`), but it is not the primary
  proposal path the way it is for finops-agent/devops-agent/control-liveness-sentinel.
- `tools.deny` blocks every write/execute tier explicitly, matching every sibling control-plane
  agent — this agent can never merge, approve, or re-run anything; it only reports.

## Alternatives considered

- **A required GitHub Actions check that blocks the merge instead of an after-the-fact agent.**
  Not mutually exclusive, and the better fix where it applies — but both real incidents happened
  specifically because branch protection's own gate was bypassed (`--admin`) or gave a false
  "clean" signal. A pre-merge check cannot audit a merge that circumvented pre-merge checks by
  construction. This agent is the backstop for exactly that class of bypass, not a replacement for
  tightening branch protection.
- **Extend control-liveness-sentinel's charter instead of a new agent.** Rejected for the same
  reason ADR-0163 rejected folding into devops-agent: governance-audit's read scope
  (`github-prs-readonly` + `read.governance`, no Prometheus) and its trigger shape (event-driven
  per merge, not a periodic metric sweep) are different enough that a shared charter would blur
  the least-privilege boundary ADR-0031 D2 asks for.
- **A dashboard tallying compliance stats, no agent.** Rejected for the same reason ADR-0160 and
  ADR-0163 rejected dashboard-only mechanisms: a dashboard is only useful to someone who remembers
  to open it. Both incidents this ADR is grounded in were discovered by chance, not by a
  compliance dashboard nobody was watching.

## Consequences

**Positive**
- Closes the "branch protection said clean, but was it actually compliant" gap directly — the
  exact blind spot both incidents exploited.
- Computes the compliance bar from `rules.yaml` itself (never a hand-maintained copy), so a future
  change to `review.money_path_approvals` or `money_path_services` is picked up automatically.
- Same governance shape as its three siblings — no new review pattern for operators to learn, and
  `tools.deny` makes the "this agent cannot itself bypass anything" guarantee structural, not just
  a policy statement.

**Negative**
- Detection, not prevention — a violation is caught after the merge, not blocked at merge time.
  The fix for an already-merged bypass is a human decision (revert, retroactive review, accept as
  a documented exception), which this agent cannot make for anyone.
- The admin/override-flag bypass check (item 5) is the weakest of the five: GitHub's API does not
  reliably expose whether `--admin` was passed to `gh pr merge`, so this check is best-effort and
  documented as a known gap rather than a hard guarantee — a determined bypass with a plausible
  paper trail could still evade it.
- A sixth Temporal-orchestrated control-plane agent adds one more workload watching a governance
  surface finops-agent/devops-agent/control-liveness-sentinel already read pieces of — acceptable,
  least-privilege-scoped duplication, but worth tracking if the control-plane agent count keeps
  growing (control-liveness-sentinel's ADR already flagged the same trend).

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101) and the existing GitHub-proposal / HITL-queue
  pattern; the GitHub-read side is a new read-only integration, not a new subsystem.

## Compliance impact

- PCI DSS: strengthens evidence that change-management controls (requirement 6.x, code-review
  discipline) were actually followed for every merge, not just claimed.
- DORA: supports Art. 9 (ICT risk detection) and Art. 5 (governance and organisation) — this agent
  is the fleet-wide re-verification layer for the repo's own change-control governance, the same
  role control-liveness-sentinel plays for operational liveness.
- GDPR: not applicable.
- PSD2: not applicable directly; a bypassed review on a payment-rail money-path service is exactly
  the kind of silent control gap this agent surfaces before it becomes a customer-facing incident.
- CNB: supports vyhláška ČNB 501/2002 Sb. change-management and internal-control expectations by
  making "was this change reviewed per policy" an auditable fact instead of an assumption.

## References

- [ADR-0031](0031-ai-agent-governance.md) — AI agent governance framework (charter shape, HITL,
  kill switch)
- [ADR-0029](0029-versioning-release-and-governance-as-code.md) — governance as code; the review
  rules this agent re-verifies
- [ADR-0030](0030-supply-chain-security-and-ssdlc-hardening.md) — money-path 2-approval + threat
  model rule (D2)
- [ADR-0112](0112-ai-finops-agent.md) — sibling control-plane agent (cost axis), the template this
  agent's shape follows
- [ADR-0119](0119-ai-devops-agent.md) — sibling control-plane agent (delivery axis)
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — sibling control-plane agent (operational
  liveness axis); same "a claim needs re-verification" premise applied to the governance axis here
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- 2026-07-07 sub-agent admin-bypass incident (lending-service, issue #266) — motivating incident 1
- plain `gh pr merge` with 0 reviews on a money-path PR — motivating incident 2
