---
date: 2026-07-13
decision-status: accepted
delivery-status: partial
authors: [jiri.raska (paired with Claude Sonnet 5)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authz, governance]
summary: "A weekly authz-policy-auditor statically analyses Rego policies and agents.yaml charters against the interceptor's real principal-type vocabulary, generalising a single hardcoded CI guard to the whole unreachable-rule defect class."
---

# ADR-0167 — authz-policy-auditor AI agent

## Context

This repo has hit the same underlying defect class twice for real, both found manually, both
already live in shipped code before being caught:

1. **Unreachable Rego rules.** `rest.rego`'s `edge-service-notification` rule was gated on
   `input.principal.type == "SERVICE"` — but `AuthorizeInterceptor.principalType()` only ever
   emits `ANONYMOUS`/`AI_AGENT`/`HUMAN`; no Keycloak client is ever granted a `SERVICE`
   classification. The rule was structurally unreachable dead code, silently denying its intended
   M2M caller the moment `AUTHZ_ENFORCE` flipped to `true` (ADR-0034 Phase 5, issue #266). A
   narrow CI guard now exists for this ONE literal pattern
   (`.github/scripts/check-no-service-principal-type.sh`), but it hardcodes the string
   `"SERVICE"` — it does not generalize to "a rule gated on a classification value the emitting
   code never produces," which is the actual defect class.
2. **Agent-id prefix mismatches.** An `AI_AGENT` principal's id carries an `agent:` prefix on the
   REST path (`AuthorizeInterceptor.principalType()`'s own convention), but NOT on the MCP
   `/tools/call` path (`openbank-agent-service` sets a bare charter id like `"ui-assistant"`
   directly). A charter lookup comparing `input.agent` against `agents.yaml` ids without first
   `trim_prefix(input.agent, "agent:")` silently never matches on the REST bridge path — found and
   fixed in `agents.rego`'s `charter` rule (PR #402), but nothing keeps re-verifying that the fix,
   or any similar comparison added later, stays correct.

Both incidents share a shape: a rule's *condition* silently diverges from the *actual runtime
vocabulary* the code that feeds it produces, and nothing was watching that specific relationship
proactively — each was found by a human manually reading the rego next to the Kotlin, not by any
automated check beyond the one narrow literal-string CI guard. `CLAUDE.md`'s own "OPA / Rego
policies" section documents a closely related, structurally identical hazard that has not (yet)
caused an incident: `rest.rego` must delegate an AI-agent REST call to `agents.allow`, never to
`agents.charter_allowed` directly, because only `allow` additionally applies `hard_denied` /
`charter_denied` / `skill_ok` — calling `charter_allowed` alone would let a fleet-wide
hard-denied tool tier, or a charter's own `tools.deny` glob, silently reach a REST action anyway.
The current code already delegates correctly, but nothing re-verifies that a future edit does not
regress it back to the unsafe form.

A fourth, related but lower-severity gap: `agents.yaml`'s `tool_tiers` registry (the vocabulary a
charter's `tools.allow`/`tools.deny` is meant to draw from) can drift from what a charter actually
references — a typo'd or renamed tool string in an `allow` list, or a `deny` glob that matches
nothing in the fleet's real tool vocabulary and so gives a false sense of restriction.

## Decision

We add **authz-policy-auditor** as a new control-plane AI agent (ADR-0031), a static analyzer over
the fleet's OPA/Rego authorization policies and the `agents.yaml` charters that feed them,
following the same Temporal-orchestrated hexagonal shape as finops-agent, devops-agent,
control-liveness-sentinel, governance-auditor, release-steward, and docs-truth-agent:

- **Reads only**, via direct repository-checkout reads of `openbank-infra/opa/policies/*.rego`
  (`agents.rego`, `copilot_tool.rego`, and their `_test.rego` siblings), `openbank-libs/
  governance/policies/*.rego` (`rest.rego`), `openbank-libs/governance/agents.yaml`, and
  `openbank-libs-runtime`'s `AuthorizeInterceptor.kt` — this agent runs from within the monorepo,
  so a full re-implementation of a code-intelligence index or a Rego AST parser is not needed
  (mirrors docs-truth-agent's/release-steward's real, best-effort grep/text-scan adapter precedent,
  and `check-no-service-principal-type.sh`'s own "stdlib-only (grep); no opa/rego-parser
  dependency" design).
- **Runs on a weekly sweep plus a reactive trigger** when any `.rego` policy or `agents.yaml`
  changes — the same "periodic sweep is the right cadence for a standing-claim check, reactive
  re-run on the touched file's own change is a cheap addition" reasoning docs-truth-agent's ADR
  already established for ADR-status drift, applied here to policy-rule drift.
- **Implements four checks, correlated into one triaged report** (`PolicyScanPort`):
  1. **Unreachable Rego rules.** Parses the actual set of principal-type values
     `AuthorizeInterceptor.principalType()` can return TODAY (not a hardcoded list — a future
     change to the interceptor's own vocabulary is picked up automatically), and flags any
     `principal.type == "X"` rule-body comparison across the canonical `.rego` sources where `X`
     is not in that set. This generalizes `check-no-service-principal-type.sh`'s single literal
     pattern to the underlying defect class.
  2. **Agent-id prefix mismatches.** Flags any `input.agent` equality comparison that lacks a
     nearby `trim_prefix(input.agent, ...)` wrap on the same line — the exact shape of the
     REST-bridge-vs-MCP-path mismatch from PR #402.
  3. **Charter/tool_tiers drift.** Two sub-checks, scoped to charters using the flat glob-list
     `tools.allow`/`tools.deny` shape (the tier/`resources:` object shape every control-plane
     agent charter — including this one — uses is a different, adjacent vocabulary and is
     deliberately out of scope, see Alternatives): (a) an `allow` token sharing a known
     `tool_tiers` namespace prefix but matching no registered entry (likely a typo within a shared
     vocabulary family), and (b) a `deny` glob matching nothing in the fleet's combined
     `tool_tiers` ∪ charter-allow-token vocabulary (a dead rule).
  4. **REST bypassing `agents.allow`.** Flags any `charter_allowed` reference found outside
     `agents.rego`/`agents_test.rego` — the only place that predicate is meant to be defined and
     consumed; a REST/MCP bridge rule must delegate to `agents.allow` instead.
  - **Check 5 (charter-vs-deployed-runtime-grant drift, issue JiRaska/open-bank#743)** — a charter's declared
    `tools.allow` in `agents.yaml` not matching what a service's `application.yaml` actually
    grants at runtime — is a deliberate bootstrap-phase stub. It needs a live-cluster or
    fleet-wide-repo-scan correlation this agent's v1 does not perform, the same "genuinely
    unbuilt, tracked as a known gap rather than faked" honesty the other agents' hardest checks
    shipped with.
- **Every finding is `draft.ticket` — never `openProposalPr`, including for check 3's dead-glob
  case that would otherwise be this agent's "one narrow mechanical case."** This deliberately
  breaks from every sibling agent's "ticket by default, mechanical PR only for the one
  deterministic case" shape: an authorization-policy defect is security-adjacent, and a wrong
  auto-fix on a rego rule or a charter is a live security exposure, not a reviewable convenience.
  `LlmDiagnosisPort.proposeFixDiff` always returns `null` by design (not "pending integration," the
  same bootstrap-stub state the other adapters ship with, but for a different, deliberate reason).
- **`requires_human` is stricter than every sibling charter's**: alongside the fleet-standard
  `every: proposal` segregation-of-duties gate, this charter adds `two_person_review:
  authz_policy_findings` — conceptually mirroring `rules.yaml: review.money_path_approvals: 2` —
  so a finding needs a second reviewer before it is closed out, not just a single approver the way
  every other control-plane agent's findings do.
- `tools.deny` blocks every write/execute tier explicitly, matching every sibling control-plane
  agent — this agent can never edit a `.rego` policy or `agents.yaml` directly; it only reports.

## Alternatives considered

- **Fold the tool_tiers/charter drift check into the tier/`resources:` object-shaped charters
  too (including this agent's own).** Rejected for v1: those charters' `tools.allow` references a
  different, coarser vocabulary (`resources: [prometheus, governance, github-pr, ...]`, a set of
  system/service names, not MCP `tool_tiers` strings), so comparing them against `tool_tiers`
  would be comparing two unrelated namespaces and would manufacture false positives on every
  control-plane agent charter, including this one, on day one. Scoping check 3 to the flat
  glob-list charters (`compliance-officer`, `ledger-domain-engineer`, `ui-assistant`,
  `rca-investigator`, `customer-copilot`) keeps the check meaningful; unifying the two vocabularies
  is a larger `agents.yaml` schema change out of scope here.
- **A full Rego AST parser (e.g. shelling out to `opa parse` / `opa eval`) instead of text
  scanning.** Rejected for v1 for the same reason `check-no-service-principal-type.sh` stays
  grep-based: no new runtime dependency, and the four checks this agent implements are shallow
  enough (a literal-value comparison, an unwrapped variable reference, a vocabulary lookup, a
  predicate-name reference) that a correctly-scoped text scan has a low false-negative rate for
  the specific incidents observed. A structural parse would close real edge cases (e.g. a
  multi-line comparison, or a rule built from a helper function this agent's line-local scan can't
  see through) — tracked as a known gap, not attempted here.
- **Fold into governance-auditor.** Rejected for the same least-privilege reasoning release-
  steward's and docs-truth-agent's ADRs used: governance-auditor's charter is scoped to merged-PR
  compliance (did a merged change follow rules.yaml's review/threat-model requirements), a
  fundamentally different question from "can this currently-committed rego rule structurally ever
  fire." A rego rule can be unreachable for months with zero merged-PR activity touching the file
  that made it unreachable (e.g. `AuthorizeInterceptor.kt` changes, not `rest.rego` itself) —
  outside governance-auditor's merged-PR-triggered scope.
- **Fold into control-liveness-sentinel.** Rejected because control-liveness-sentinel asks whether
  code that exists is still *behaving* (a heartbeat, an event consumer) — a runtime-liveness axis
  — while this agent asks whether a policy rule's condition can structurally ever be satisfied by
  the vocabulary the rest of the system actually produces, a static-analysis axis with no runtime
  signal at all. The two check fundamentally different things even though both are "a standing
  claim is not proof it still holds" in spirit.
- **Auto-fix the one deterministic case (a `SERVICE`-gated rule) via a mechanical PR.** Rejected —
  see Decision. Even a mechanically obvious rego edit changes what a live authorization rule does;
  this agent's charter treats "obviously correct in isolation" and "safe to merge without human
  judgment" as different bars for a security policy, unlike docs-truth-agent's or release-steward's
  single-line `Delivery-Status:`/`version.txt` fixes, which affect metadata, not access control.

## Consequences

**Positive**
- Generalizes `check-no-service-principal-type.sh`'s one hardcoded pattern into a proactive,
  periodic re-verification against `AuthorizeInterceptor`'s ACTUAL emitted vocabulary — a future
  change to that vocabulary (e.g. a new principal type) is picked up automatically, not just the
  one string this repo has already been burned by.
- Closes the agent-id-prefix-mismatch defect class from PR #402 with a standing, periodic check
  instead of trusting the one-time fix never regresses.
- Makes the `charter_allowed`-vs-`agents.allow` invariant CLAUDE.md already documents in prose into
  an actively re-verified fact, not just a comment a future editor might not read.
- Same governance shape as its five siblings — no new review pattern for operators to learn — while
  its stricter `two_person_review` HITL gate signals, structurally, that this agent's findings
  carry more weight than a documentation or cost nit.

**Negative**
- Detection only, never correction, even for the fleet's usual "one narrow mechanical case" —
  strictly less automation than every sibling agent, a deliberate trade against this agent's
  security-adjacent blast radius.
- A text-scan-based check has real false-negative and false-positive edges of its own: a rule
  built through a helper function or a multi-line comparison the line-local scan can't see through
  is invisible to it, and check 3's namespace-prefix heuristic can still miss a cross-word typo in
  a token's own namespace segment. A `draft.ticket` gives a human the citation, not a verdict — the
  same shallow-and-honest posture docs-truth-agent's ADR already accepted for its own grep-based
  checks.
- A seventh Temporal-orchestrated control-plane agent adds one more workload watching a governance
  surface other agents already read pieces of (`read.governance` overlaps with governance-auditor,
  release-steward, and docs-truth-agent) — the same acceptable, least-privilege-scoped duplication
  trend the prior agents' ADRs already flagged.

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101) and the existing GitHub-proposal / HITL-queue
  pattern; the policy-scan side is a new but simple integration (local file reads plus grep against
  a repo checkout), the same pattern docs-truth-agent's `RepoScanPort` and release-steward's
  `RepoStateReadPort` already established.

## Compliance impact

- PCI DSS: strengthens access-control change-management evidence (requirement 7.x/8.x) that an
  authorization rule's condition actually corresponds to the runtime identity vocabulary it is
  meant to gate, not just that the rule text was reviewed once at merge time.
- DORA: supports Art. 5 (ICT risk management framework) and Art. 9 (ICT risk detection) — a
  structurally unreachable authorization rule is exactly the kind of latent ICT risk (a control
  that looks present but cannot fire) this agent surfaces before an enforcement flip turns it into
  a live incident, as already happened once (issue #266).
- GDPR: supports Art. 32 (security of processing) by continuously re-verifying that access-control
  rules gating PII-bearing endpoints resolve against the identity classifications the system
  actually produces.
- PSD2: supports SCA/strong customer authentication's underlying access-control assurance —
  the same class of defect (an authorization rule that silently never fires) is the mechanism by
  which a payment-rail endpoint could end up effectively ungated once enforcement is live.
- CNB: supports vyhláška ČNB 501/2002 Sb. access-control and change-management expectations by
  making "can this authorization rule actually fire the way its author intended" an auditable,
  continuously re-verified fact instead of an assumption that holds only until the next manual
  review happens to catch a drift.

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — AI agent governance framework (charter
  shape, HITL, kill switch)
- [ADR-0034](0034-unified-opa-authz-mcp-and-rest.md) — unified OPA authorization (single sidecar
  for MCP tool-calls and REST endpoints); both motivating incidents (SERVICE-principal, agent-id
  prefix) live under this ADR's Phase 5/D2 rollout
- [ADR-0018](0018-opa-for-fine-grained-authz.md) — OPA for fine-grained authorization; the MCP
  `/tools/call` policy-gate framework `agents.rego` implements
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — sibling control-plane agent
  (operational-liveness axis); explicitly contrasted above
- [ADR-0164](0164-governance-auditor-ai-agent.md) — sibling control-plane agent (merged-PR
  compliance axis); explicitly contrasted above
- [ADR-0165](0165-release-steward-ai-agent.md) — sibling control-plane agent (release/version axis)
- [ADR-0166](0166-docs-truth-agent-ai-agent.md) — sibling control-plane agent
  (documentation-vs-code drift axis); same "ticket by default" disposition shape this agent
  deliberately narrows further (ticket-ONLY, no mechanical-PR case)
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- [ADR-0155](0155-four-eyes-enforcement-for-money-path-actions.md) — four-eyes / second-approver
  pattern this charter's `two_person_review` requirement conceptually mirrors
- Issue #266 — the SERVICE-principal dead-code incident (ADR-0034 Phase 5 rollout)
- PR #402 — the agent-id-prefix-mismatch fix (`agents.rego`'s `charter` rule)
- `CLAUDE.md` "OPA / Rego policies (ADR-0031/ADR-0034)" — documents the
  `charter_allowed`-vs-`agents.allow` bypass hazard this agent's check 4 re-verifies
- `.github/scripts/check-no-service-principal-type.sh` — the narrow, single-literal CI guard this
  agent's check 1 generalizes
