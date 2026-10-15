---
date: 2026-10-15
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, governance, compliance, audit]
summary: "Phase-4 money-path agent merges must carry two recorded distinct-human approvals in agent_merge_record; GitHub approvals stay advisory because branch protection cannot enforce them (#2183)."
---

# ADR-0340 — Money-path agent merges require recorded two-person review (amends ADR-0031 phase 4)

## Context

ADR-0031 phase 4 expands agent development work to money-path services under the
condition of "enforced two-person review and a threat model for every money-path
contribution", tied to `rules.yaml: review.money_path_approvals: 2`. The gap analysis
behind issue #12885 found that this condition has no enforceable implementation today:

- `rules.yaml` itself classifies the two-approval rule as `aspirational_not_enforced`:
  the repository runs on a GitHub Free plan with a single write account (#2183), so
  branch protection cannot require approving reviews (`required_approving_review_count: 0`),
  and the author-≠-approver rule is equally unavailable. Measured compliance on recent
  money-path PRs is 0 %.
- Runtime four-eyes (ADR-0155) is a *separate* mechanism (maker-checker on REST calls),
  remains `authz.four-eyes.enforce=false` fleet-wide, and cannot substitute for review of
  code changes in any case.
- Phase 3 (PR #12822) built the missing piece: a recorded-human-merge pipeline —
  `record-agent-merge` workflow → `POST /api/v1/agent-merge-records` → `agent_merge_record`
  (V6) + `agent.pr.merged` audit with `actorType=HUMAN`, out-of-band of agent telemetry.

Without an enforceable definition, phase 4 has no testable exit criterion, and an
auditor cannot distinguish "two people agreed" from "one person merged".

## Decision

We will define "enforced two-person review" for money-path agent merges as
**recorded-two-approver merge**, amending the ADR-0031 phase-4 exit requirement:

1. `agent_merge_record` gains two distinct human approver identities per money-path
   agent merge — `approver_1_login`, `approver_2_login`, each with timestamp and
   recorded decision reason. Both must differ from each other and from the merge
   author; a self-approval is rejected at record time.
2. The record flows through the existing phase-3 pipeline (workflow → agent-service
   API → `agent_merge_record` + audit); the recorder resolves approvers from the PR
   review history and fails the record (never the merge) if two distinct approvals
   are not on file.
3. The `governance-auditor` agent (declared in `agents.yaml`) flags any money-path
   agent merge lacking both approval records — making the check continuous and
   post-hoc auditable rather than a one-time gate.
4. Human-authored money-path PRs keep the current rules (`money_path_approvals: 2`
   stays aspirational until #2183 is resolved); this ADR governs **agent** merges,
   where we control the whole pipeline end-to-end.
5. ADR-0155 runtime four-eyes remains orthogonal and unchanged; this ADR does not
   flip `authz.four-eyes.enforce`.

## Alternatives considered

- **Wait for GitHub branch protection (#2183)** — require two approving reviews via
  platform settings once a paid plan / second write account exists. Pros: standard,
  no custom machinery. Cons: unbounded wait; today it enforces nothing, which is the
  actual gap; measured compliance is 0 % *now*. Rejected as the enforcement mechanism,
  kept as a future hardening step.
- **Reuse runtime four-eyes (ADR-0155) as the review mechanism** — maker-checker on
  the merge action itself. Pros: existing vocabulary and OPA data plane. Cons: it
  gates REST calls, not code review; it is fleet-disabled and its self-approval guard
  lacks a test (#3349); stretching it to PR review would blur two distinct controls.
  Rejected.
- **Block the merge in CI when approvals are missing** — fail the PR check. Cons: the
  merge is performed by a human with admin rights on a Free-plan repo; a failing check
  is advisory the moment it inconveniences someone, and it duplicates what the record
  pipeline can prove positively. Rejected in favour of positive recording + continuous
  audit.

## Consequences

**Positive**
- Phase 4 gains a testable, machine-checkable exit criterion that works on the current
  GitHub plan.
- The evidence (two approver identities + timestamps + reasons, in the same
  out-of-band store as the merge record) is auditor-friendly and tamper-evident by
  construction of the phase-3 pipeline.
- The same record later feeds phase-5 provenance (AI attribution in release evidence).

**Negative**
- Custom machinery to maintain: schema migration on `agent_merge_record`, recorder
  logic resolving PR reviewers, auditor rules. If GitHub ever enforces reviews natively
  (#2183), the two mechanisms must be reconciled.
- A merge can still happen without approvals; the control is *recorded + audited*,
  not blocking. This is a deliberate trade-off documented here so no future reader
  mistakes it for a hard gate.

**Neutral**
- Human-authored money-path PRs are out of scope; their review rule is unchanged.

## Compliance impact

- PCI DSS: Req. 7 least privilege / change control — two-person review evidence for
  agent changes touching money-path components supports Req. 6 change-control
  expectations.
- DORA: Art. 8–10 ICT risk management and change documentation — recorded approvals
  are change-documentation evidence.
- GDPR: not applicable — no personal-data processing changes; approver logins are
  existing operator identities already present in the audit trail.
- PSD2: not applicable — no change to SCA/consent gating; agents still never act on
  money paths.
- CNB: auditability of automated decisioning — strengthens the evidence chain for
  agent-authored changes in the banking perimeter.

## References

- ADR-0031 (AI agent governance and operations) — phase-4 exit requirement, amended by this ADR
- ADR-0030 (money-path threat models) — the "threat model" half of the phase-4 requirement, unchanged
- ADR-0155 (four-eyes) — orthogonal runtime mechanism, explicitly out of scope
- #12885 (phase-4 gap analysis), #2183 (GitHub plan constraint), #3349 (four-eyes self-approval guard test)
