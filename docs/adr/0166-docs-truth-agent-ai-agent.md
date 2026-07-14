# ADR-0166 — docs-truth-agent AI agent

Date: 2026-07-13
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): jiri.raska (paired with Claude Sonnet 5)

## Context

ADR-0139 and ADR-0140 both carried `Delivery-Status: Planned` (later corrected) describing a
feature store as "not yet implemented" — while the feature store had, in fact, already shipped as
`OnlineFeatureStore`. Nobody caught the drift between the ADR's own status line and the code until
a duplicate feature store was designed and partially built against the stale "not yet implemented"
claim, and that duplicate work had to be reverted (PR #944 → #950). The ADR was Accepted, its
status line was simply wrong, and nothing ever re-read the code to check it.

A second, structurally different incident makes the same point from the other direction. ADR-0114
(`Delivery-Status: Shipped`) is correct that `openbank-standing-order-service` ships a daily
scheduler and an outbox publish of `standing-order.due.v1` — but the ADR's own scheduler
doc-comment additionally claimed a downstream payment-rail consumer existed for that event. It
did not. Nothing consumed `standing-order.due.v1` for weeks (issue #889) before the gap was found.
ADR-0160 was written the same day as the motivating incident write-up and shipped four mechanisms
to convert "an agent has to remember this is true" into a continuously re-verified fact for
*runtime* behaviour: an event-consumer-liveness CI gate, a lineage-vs-code CI gate, a shared
`WorkflowLivenessWatchdog` Prometheus primitive, and control-liveness-sentinel (this repo's
periodic agent that pages when a scheduled job or event consumer goes quiet).

ADR-0160's mechanisms verify that code which exists and is wired up keeps *behaving* correctly at
runtime — a heartbeat stops, an event queue backs up, a reconciliation control goes stale. They do
not, and are not designed to, check whether an ADR's own prose *about* the code is still accurate.
The ADR-0139/0140 incident and the ADR-0114 doc-comment incident are both failures of the second
kind: a documentation claim (a `Delivery-Status:` line, or a doc-comment asserting a downstream
consumer exists) silently diverged from the code, and nothing was watching that specific
relationship. This is a narrower, more static-analysis-flavored problem than ADR-0160's runtime
liveness checks — it is asking "does this file/class/gate the ADR names actually exist and match
what the ADR says about it," not "is the thing that exists still behaving." Two additional gaps
compound the risk: `rules.yaml`'s own `enforced: advisory` / `enforced: enforce` gate-graduation
flags (ADR-0144) can drift from what an ADR or doc claims about that same gate's enforcement state,
and a `Delivery-Status: Shipped`/`Complete` ADR's own claimed artifact can rot or be renamed without
the ADR ever being revisited.

## Decision

We add **docs-truth-agent** as a new control-plane AI agent (ADR-0031), a periodic, grep-based
ADR-status-vs-code drift detector, following the same Temporal-orchestrated hexagonal shape as
finops-agent (ADR-0112), devops-agent (ADR-0119), control-liveness-sentinel, governance-auditor,
and release-steward:

- **Reads only**, via direct repository-checkout reads of every `docs/adr/*.md` file's
  `Delivery-Status:` line and the artifact(s) it names (a class, file, config key, or CI gate
  script), and `openbank-libs/governance/rules.yaml`'s `enforced:` flags.
- **Runs on a periodic sweep (weekly) rather than a reactive webhook** — unlike control-liveness-
  sentinel's runtime-anomaly triggers or release-steward's release-please-merge trigger, an ADR's
  status going stale is not an event with a natural trigger moment; a periodic full-repo sweep is
  the right cadence. Reactively re-running the sweep when any `docs/adr/*.md` file changes is a
  reasonable, cheap addition and is wired as this agent's `reactive` trigger.
- **Checks three things per run, correlated into one triaged report**:
  1. **Shipped/Complete claims a code artifact that isn't there or isn't wired up.** For every ADR
     with `Delivery-Status: Shipped` or `Complete`, a lightweight grep-based existence check per
     named artifact (a class name, a file path, a CI gate script, a config key) — e.g. an ADR
     claiming "ships as `OnlineFeatureStore`" should find a matching class; an ADR claiming a CI
     gate is enforced should find the gate script exists and is not left in an advisory-only state.
     This is deliberately shallow (existence + a wired-up signal such as being referenced from a
     workflow/build file), not deep semantic verification — the same "did anyone even look" bar
     control-liveness-sentinel's siblings apply to their own axes.
  2. **Planned/Partial claims lag a fully-built, wired implementation.** The ADR-0139/0140 case in
     reverse: for every ADR with `Delivery-Status: Planned` or `Partial`, check whether a
     fully-built, wired implementation already exists that the ADR text doesn't mention — the
     precise failure that caused PR #944's duplicate work.
  3. **Enforcement-status cross-reference.** Compare an ADR's or doc's prose claim that a gate is
     "enforced" (or "advisory-only") against `rules.yaml`'s own `enforced:` flag for that gate
     (ADR-0144 gate-graduation), flagging either direction of mismatch.
- **Findings are primarily `draft.ticket`, with a rare mechanical `Delivery-Status:`-line-only fix
  PR when the evidence is unambiguous.** Correcting an ADR's substantive content — what actually
  shipped, why, and what remains — is a judgment call only a human who reads both the ADR and the
  code can make; this agent does not attempt to rewrite ADR prose. `openTicket` is the default
  disposition for every finding. `openProposalPr` stays available in the `GitHubProposalPort` for
  the narrow, mechanical case where the evidence is unambiguous enough that flipping just the
  `Delivery-Status:` line (nothing else in the file) is a safe, reviewable one-line diff — mirroring
  release-steward's own "ticket by default, mechanical PR only for the one deterministic case"
  shape.
- `tools.deny` blocks every write/execute tier explicitly, matching every sibling control-plane
  agent — this agent can never edit an ADR's decision content, merge a PR, or write to
  `rules.yaml`; it only reports and, for the one narrow case, proposes a single-line diff a human
  must still approve and merge.

## Alternatives considered

- **A CI gate that fails a PR touching `docs/adr/*.md` if the named artifact doesn't exist.**
  Rejected as the sole mechanism for the same reason ADR-0160's Context section gives for the
  event-consumer-liveness and lineage-vs-code gates it did ship as CI gates rather than agents: a
  PR-scoped, diff-triggered check only fires when someone touches the ADR file itself. The
  ADR-0139/0140 incident is exactly the case where nobody touched the stale ADR for months while
  the code around it kept shipping — a diff-scoped gate is structurally blind to drift introduced
  by a *different* file's PR. A periodic, fleet-wide sweep is required to catch this class, the same
  argument release-steward's ADR made for its four release/version-axis checks.
- **Fold into control-liveness-sentinel.** Rejected for the same least-privilege reasoning
  governance-auditor's and release-steward's ADRs used: this agent's read scope (ADR prose plus a
  grep-based code-existence check) and its periodic-sweep trigger are different enough from
  control-liveness-sentinel's Prometheus-gauge/event-consumer runtime-liveness scope that a shared
  charter would blur the ADR-0031 D2 least-privilege boundary. The two are also genuinely different
  in kind, not just scope: control-liveness-sentinel asks whether code that exists is still
  *behaving*; this agent asks whether an ADR's own words about the code are still *true*.
- **Fold into governance-auditor.** Rejected because governance-auditor's charter is scoped to
  merged-PR compliance (did a merged change follow the rules it was supposed to), a fundamentally
  different question from "does this ADR's standing claim about the repo's current state still
  hold" — an ADR can be stale for months with zero merged-PR activity touching it at all, which is
  outside governance-auditor's merged-PR-triggered scope.
- **Deep semantic verification (an LLM reads the ADR and the actual implementation and judges
  correctness).** Rejected as the *detection* mechanism, though it remains available as the
  diagnosis step once a finding is flagged. A grep-based existence/wiring check is cheap, has a low
  false-negative rate for the specific failure class observed (an artifact that plain doesn't exist,
  or an artifact that trivially does), and keeps this agent's `tools.allow` scope narrow — read-only
  file access plus `rules.yaml`, no code-execution tier. Deep semantic judgment about whether a
  Partial ADR's remaining scope is *accurately* described stays a human review task.

## Consequences

**Positive**
- Closes the exact gap the ADR-0139/0140 incident exposed: an ADR's own `Delivery-Status:` line can
  now be periodically re-verified against the code instead of trusted indefinitely.
- Closes the ADR-0114/#889 gap from the other direction: a doc-comment or ADR claim of a downstream
  wiring (a consumer, a caller, an enforced gate) that never existed no longer survives silently.
- A narrower, cheaper complement to ADR-0160's runtime-liveness mechanisms — static doc-vs-code
  verification catches a different, earlier failure mode (a stale claim) than a runtime watchdog
  (a claim that was once true and stopped being true operationally).
- Same governance shape as its four siblings — no new review pattern for operators to learn, and
  `tools.deny` makes "this agent cannot itself rewrite an ADR's decision" structural.

**Negative**
- Detection, almost never correction — flipping a `Delivery-Status:` line is the one narrow
  mechanical case; every other finding is a ticket, because judging whether an ADR's *prose*
  (not just its status line) is still accurate needs a human who reads both the ADR and the current
  code.
- A grep-based existence check has real false-negative and false-positive edges: a renamed class
  that still does the same job looks "missing," and a same-named class from an unrelated ADR can
  look like a match. The check is intentionally shallow — a `draft.ticket` gives a human the
  citation, not a verdict.
- A sixth Temporal-orchestrated control-plane agent adds one more workload watching a governance
  surface the other five already read pieces of — the same acceptable, least-privilege-scoped
  duplication trend the prior four agents' ADRs already flagged, still worth tracking as the
  control-plane agent count keeps growing.

**Neutral**
- No new infrastructure: reuses Temporal (ADR-0101) and the existing GitHub-proposal / HITL-queue
  pattern; the repo-scan side is a new but simple integration (local file reads plus grep against a
  repo checkout), the same pattern release-steward's `RepoStateReadPort` already established.

## Compliance impact

- PCI DSS: strengthens change-management/documentation-accuracy evidence (requirement 6.x/12.x)
  that architectural decision records — the repo's own record of *why* and *what* was decided —
  stay verifiably accurate, not just that individual code changes are reviewed.
- DORA: supports Art. 9 (ICT risk detection) — a stale "not yet implemented" claim that causes
  duplicate work, or a stale "enforced" claim that overstates a control's real coverage, is exactly
  the kind of documentation-driven operational risk this agent surfaces before it causes rework or
  a false sense of coverage.
- GDPR: not applicable.
- PSD2: not applicable directly; a stale claim about a payment-rail control's delivery or
  enforcement status is the kind of silent documentation drift this agent is designed to catch
  before it is relied upon operationally.
- CNB: supports vyhláška ČNB 501/2002 Sb. change-management and documentation expectations by
  making "does the repo's own decision record still match reality" an auditable, continuously
  re-verified fact instead of an assumption that holds only until the next person happens to notice.

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — AI agent governance framework (charter
  shape, HITL, kill switch)
- [ADR-0029](0029-versioning-release-and-governance-as-code.md) — governance as code; the
  `rules.yaml` gate-graduation flags this agent's enforcement-status cross-reference check reads
- [ADR-0160](0160-end-to-end-integration-liveness-and-drift-detection-standard.md) — sibling
  standard for *runtime* claim verification (event consumption, scheduled-job heartbeats); this
  agent applies the same "a standing claim is not proof it still holds" premise to *documentation*
  claims instead, a narrower and more static-analysis-flavored check
- [ADR-0112](0112-ai-finops-agent.md) — sibling control-plane agent (cost axis), the template this
  agent's shape follows
- [ADR-0119](0119-ai-devops-agent.md) — sibling control-plane agent (delivery axis)
- [ADR-0163](0163-control-liveness-sentinel-ai-agent.md) — sibling control-plane agent
  (operational-liveness axis); explicitly contrasted above
- [ADR-0164](0164-governance-auditor-ai-agent.md) — sibling control-plane agent (merged-PR
  compliance axis)
- [ADR-0165](0165-release-steward-ai-agent.md) — sibling control-plane agent (release/version axis);
  same "ticket by default, mechanical PR only for the one deterministic case" disposition shape
  this agent follows
- [ADR-0101](0101-temporal-durable-execution.md) — Temporal orchestration
- [ADR-0139](0139-ml-decisioning-platform.md) / [ADR-0140](0140-feature-store-topology.md) —
  the motivating `Delivery-Status: Planned`-vs-shipped-`OnlineFeatureStore` incident (PR #944 → #950)
- [ADR-0114](0114-standing-order-execution-model.md) — the motivating doc-comment-claims-a-consumer
  incident (issue #889)
- [ADR-0144](0144-gate-graduation-advisory-rules-carry-an-enforcement-deadline.md) — the
  advisory-to-enforce gate-graduation policy this agent's `rules.yaml` cross-reference check
  re-verifies
