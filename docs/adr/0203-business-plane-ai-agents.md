---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, governance, fraud, compliance]
summary: "Six business-plane agents extend the agent estate from governing the platform to serving customers, each read-only with a human disposition; fraud-triage ships first, and collections is deferred as AI Act high-risk."
---

# ADR-0203 — Business-plane AI agents

## Context

The agent estate has one shape. Of the fifteen agents in `openbank-libs/governance/agents.yaml`,
`plane` is `control` or development for nearly all of them: finops, devops, control-liveness-sentinel,
governance-auditor, release-steward, docs-truth, authz-policy-auditor, flaky-test-hunter,
rca-investigator. They watch the *platform*. Only `customer-copilot` (ADR-0089), `mcp-anonymous`
(ADR-0181) and `ap2-anonymous` (ADR-0193) face outward, and the latter two are read-only or
evidence-only by design.

Nothing watches the *bank's work*. Concretely, and each of these is a queue or a task that exists today
and is entirely manual:

- ADR-0032 holds sub-threshold sanctions/AML hits for an analyst rather than rejecting them. That queue
  has no context assembly — the analyst opens several services by hand.
- ADR-0102 committed to LLM-assisted KYC document extraction as one of three differentiation tracks and
  it is `accepted/planned`; the extraction does not exist.
- dispute-service has no evidence assembly; a disputed transaction's supporting material is gathered
  manually.
- ADR-0200's campaign definitions have to be authored by someone with no help.

Meanwhile the governance substrate for adding an agent is complete and cheap: `agents.yaml` with a
per-agent charter, `docs/agents/<id>.md` with id-parity enforced by
`.github/scripts/check-agent-charter-registry.sh`, the ADR-0034 sidecar authorizing every tool-call,
ADR-0031's AI-attributed audit, ADR-0148's prompt registry and evals gate, and `ml-systems.yaml` for
the AI Act inventory. Adding a governed agent is configuration plus a service, not a new control plane.

Two counterweights, both real:

**The gateway is not deployed.** ADR-0174 records that ADR-0031's LiteLLM/vLLM/Anthropic gateway
topology *is not deployed at all*, so there is no per-agent token accounting and no egress control over
prompts leaving for a US provider (the ADR-0175 open exposure). Six new agents multiply a cost nobody
currently measures.

**The estate already has an unfixed privilege problem.** agent-service holds a blanket `ROLE_OPERATOR`
privilege-escalation issue (draft advisory GHSA-r5r5-2934-w92f). Adding agents before that is understood
risks propagating the pattern rather than the governance.

Why now: ADR-0199 and ADR-0201 create the read surface these agents need, ADR-0202 defines how they
cooperate, and the fraud queue is a case where the human is already doing the work and the agent's only
job is preparation.

## Decision

We will add six agents, all `plane: business`, all read-only with a human disposition, declared in
`agents.yaml` with charters under `docs/agents/`. The list is deliberately ordered by build sequence,
and two of the six are explicitly deferred.

**D1 — `fraud-triage-agent` ships first.** It reads the ADR-0032 REVIEW queue and, per held item,
assembles context from the ADR-0199 view, transaction history and the ADR-0201 feature values, then
proposes a verdict with its reasoning and the evidence it used. The analyst decides. It is first because
the human queue, the human, and the decision authority all already exist — the agent adds preparation
and removes nothing, which is the lowest-risk possible first business agent. `requires_human: every
proposal`; no tool at `write` or `execute` tier.

**D2 — `dispute-evidence-agent`.** Assembles an evidence pack for a disputed transaction: the
transaction record, relevant statements, any AP2 mandate the ADR-0193 verifier holds, the notification
history, and the customer's account context. Output is a document proposal a human reviews before it
becomes a case position. This is the clearest ADR-0202 D1 consumer: a fraud-triage finding on the same
transaction is an input.

**D3 — `kyc-extraction-agent`.** Delivers the ADR-0102 track that was committed and never built: extract
structured fields from an identity or proof-of-address document and propose them for the onboarding
officer to confirm. Extraction never auto-approves an onboarding, and a low-confidence field is
surfaced as unknown rather than guessed. Note the standing dependency: document-service's PAdES sealing
still runs on a throwaway certificate because the OpenBao `document-service-signing-seal` key was never
seeded (#1284/#2029), so this agent must not be described as producing evidential output.

**D4 — `campaign-copilot`.** Helps a marketer author an ADR-0200 campaign: proposes a segment from a
plain-language description, drafts which existing catalogue templates fit which step, and explains what
the ADR-0201 NBA ranking did. It composes from the catalogue and never authors message text — ADR-0176
D4's refusal of free-text bodies is not relaxed for an LLM, which is the single most important
constraint on this agent. Activation still needs the ADR-0200 D5 four-eyes approval.

**D5 — `finance-coach`, as an extension of customer-copilot, not a new agent.** Proactive insights in
the mobile app (an expiring rate fix, a recurring fee, an FX pattern) reuse the existing
`customer-copilot` charter and its ADR-0089 constraint that the model routes and narrates while every
figure comes from a tool call. It gets no new charter because it is not a new principal: it is the same
customer-facing assistant with a scheduled trigger. Any push it produces is a marketing or product
communication and is therefore gated by ADR-0198's consent and ADR-0200's suppression rules — a coach
insight is not a licence to bypass the marketing gate.

**D6 — `collections-agent` is deferred, and the reason is recorded.** Detecting delinquency and proposing
a repayment plan touches creditworthiness and a customer's financial position under adverse
circumstances. That is ADR-0142 territory (AI Act Annex III.5(b)) and needs adverse-action reasoning,
deterministic affordability supremacy and four-eyes on declines before an agent goes anywhere near it.
It is listed here so the gap is visible, and it does not ship with this ADR.

**D7 — Common constraints for all of the above.** No tool at `write` or `execute` tier, ever;
`data_scope.pii` declared honestly per agent (unlike the control-plane agents, these read customer data,
and `pii: none` would be a false declaration); `requires_human` on every output; `tokens_per_run` and
`runs_per_day` limits set from a measured first run rather than guessed; kill-switch honoured; audit
capture including `model_id`, `prompt_hash`, `tool_calls`, `policy_decision` and `human_approved_by`, per
the `agents.yaml` defaults. Each agent gets an `ml-systems.yaml` entry with its own risk classification
in the same PR that declares it, generated rather than hand-edited (#2216).

**D8 — Sequencing gates, stated as blocking.** No business agent that reads customer data through
mcp-service ships before ADR-0195 / #2206, because `resolveContext()` currently returns a constant and
every caller would authorize identically. The LiteLLM gateway (ADR-0031/0174) should be deployed before
the second agent, so token cost has a denominator. And each newly graduating service must be added to
every fleet-sweep exclusion list it was previously absent from — the standing-order case shipped missing
both Kafka mTLS and its OPA sidecar precisely this way.

**D9 — Licensing.** All six are agent-plane and move no money, so they satisfy the ADR-0197 property test
and are AGPL-3.0-only, enumerated in `rules.yaml agpl_modules` (twelve today). `finance-coach` adds no
module, since it extends copilot-service, which is already AGPL.

## Alternatives considered

- **Add no business agents; keep the estate governance-only.** Zero new customer-data exposure, zero new
  token spend, and the estate stays uniform and easy to reason about. Genuinely the safe answer, and it
  is the status quo. Rejected because the platform's stated differentiation is *governed* AI in banking,
  and an estate that only ever watches CI demonstrates the governance without demonstrating that it
  survives contact with customer data — which is the claim a regulator or an adopter actually wants
  tested. ADR-0102 also already committed two of these tracks.
- **Build the six as features inside their owning services rather than as agents.** No new deployments,
  no charters, and the logic sits next to the domain it serves. Rejected: a charter, a `data_scope`, a
  tool allow-list, a token limit and a kill switch are exactly the controls that make an LLM in a bank
  defensible, and a feature flag inside fraud-service has none of them. The agent framing *is* the
  governance.
- **One general-purpose "banking assistant" agent covering all six use cases.** Fewer moving parts, one
  charter, one deployment. Rejected on least privilege: the union of six `data_scope`s is a very large
  grant with a single blast radius, and it is the same objection already recorded against an orchestrator
  agent in ADR-0202 and against agent-service's blanket `ROLE_OPERATOR`. Narrow charters are the point.
- **Start with `collections-agent`, since the commercial value is most direct.** Highest measurable ROI of
  the six — recovered arrears are money. Rejected per D6: it is the one item on the list that engages
  Annex III.5(b) and touches customers in financial distress, so it is the worst possible first agent
  regardless of its return.
- **Start with `campaign-copilot`, since ADR-0200 needs authoring help.** Rejected on sequencing rather
  than on merit: it depends on ADR-0198, ADR-0200 and ADR-0201 all landing first, whereas fraud-triage
  depends only on a queue that exists today.
- **Give each agent write tools with a rollback rather than a human approval.** Faster, and rollback is a
  real control. Rejected: ADR-0031's model-proposes/bank-disposes line is the estate's load-bearing
  invariant, and the repo has a first-hand record of an agent with admin rights taking an unprompted
  bypass action when the prompt merely omitted a prohibition. A rollback also cannot un-send a message or
  un-decline a customer.

## Consequences

**Positive**
- The estate stops being governance-only, so the platform's AI governance claim gets tested against
  customer data, which is where it matters and where nothing currently exercises it.
- Delivers the ADR-0102 KYC extraction track that has been `planned` since it was accepted.
- The ADR-0032 analyst queue gains context assembly without changing who decides.
- Each agent is a narrow charter with an audit trail, so an adopter can read `agents.yaml` and see
  exactly what each is permitted to do.
- ADR-0202 gets its first real collaboration case (fraud-triage into dispute-evidence) rather than a
  hypothetical one.

**Negative**
- These agents read customer PII, which no control-plane agent does. `data_scope.pii` moves from `none`
  to a declared tier for the first time, and the ADR-0118 tiering and ADR-0175 residency questions now
  apply to prompt content — not only to stored data. The undeployed gateway makes that exposure harder
  to bound, which is why D8 gates on it.
- Six agents at once would be too many. The build order is part of the decision, and a temptation to
  parallelise it should be read as a risk rather than as efficiency.
- Token spend grows with no measurement in place until the gateway lands; `tokens_per_run` caps each run
  but nothing caps the aggregate.
- Six new services (five, given finance-coach) each need a Postgres or not, an OPA sidecar bundle,
  NetworkPolicy edges, a charter, an AI Act entry and a `version.txt`. Editing `rules.yaml` or any
  `.rego` restamps every service's OPA bundle, so each of these PRs carries a large generated diff with a
  short shelf life.
- `collections-agent` being deferred means the highest-commercial-value case stays manual, and that will
  be questioned repeatedly.

**Neutral**
- Whether each agent is its own service or several share one deployment is an implementation choice; the
  charter boundary is what this ADR fixes.
- `finance-coach` extending customer-copilot rather than becoming its own agent means the charter count
  goes from 15 to 20, not 21.

## Compliance impact

- PCI DSS: not applicable for five of the six. `dispute-evidence-agent` may touch card-transaction
  metadata, so its `data_scope` must exclude PAN explicitly rather than by assumption — synthetic PANs and
  the card vault stay out of scope per ADR-0194.
- DORA: each agent is an internal component, but the LLM provider behind them is an ADR-0174 ICT third
  party whose register entry and exit position already exist and now carries more load. Kill-switch and
  human-approval controls are the operational-resilience story.
- GDPR: Art. 5(1)(c) minimisation — a `data_scope` is the mechanism, and it must be the minimum each
  agent needs rather than what is convenient; Art. 22, which is not engaged because every output is a
  proposal a human disposes of, and that is exactly why D7's `requires_human` is not negotiable; Art. 30
  for each new processing purpose; Art. 32 for prompt content leaving the estate, which is the ADR-0175
  exposure. `kyc-extraction-agent` processes Art. 9-adjacent identity documents and needs its charter
  reviewed on that basis specifically.
- PSD2: not applicable — no agent here initiates a payment or grants account access. An agent's read of
  account data is internal-purpose, not TPP access.
- CNB: not applicable — no agent output feeds a statutory report; finrep and anacredit keep their own
  authoritative sources.

EU AI Act: each agent needs its own `ml-systems.yaml` classification rather than inheriting one. On the
face of it all five that ship are limited-risk decision-support with human oversight (Art. 14), but
`kyc-extraction-agent` sits closest to a high-risk boundary because identity verification supports access
to a service, and `collections-agent` is deferred in D6 precisely because it crosses into Annex III.5(b).
The 2026-08-02 milestone applies, so a registry entry must land with the agent and not after it.

## References

- [ADR-0031](0031-ai-agent-governance-and-operations.md) — agents-as-code, model-proposes/bank-disposes,
  AI-attributed audit.
- [ADR-0102](0102-agentic-ai-differentiation.md) — the committed tracks, including the unbuilt KYC
  extraction D3 delivers.
- [ADR-0032](0032-synchronous-sanctions-aml-screening-gate-in-payment-execution.md) — the analyst REVIEW
  queue D1 prepares work for.
- [ADR-0156](0156-agent-charters-as-markdown-alongside-agents-yaml.md) — the charter layer and parity
  gate every new agent must satisfy.
- [ADR-0148](0148-ai-assurance-prompt-registry-evals-gate-and-eu-ai-act-mapping.md) — prompt registry,
  evals gate and the Annex IV inventory.
- [ADR-0089](0089-customer-facing-ai-assistant.md) — the customer-copilot charter D5 extends rather than
  duplicates.
- [ADR-0142](0142-credit-decisioning-engine.md) — the high-risk controls that keep `collections-agent`
  deferred.
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — blocking per D8;
  blocker #2206.
- [ADR-0202](0202-agent-to-agent-collaboration-over-proposal-events-mcp-and-temporal.md) — how these
  agents hand work to each other.
- [ADR-0197](0197-agpl-open-core-boundary-covers-the-whole-agent-plane.md) — the licence property that
  puts all six in `agpl_modules`.
- [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) and
  [ADR-0175](0175-data-residency-and-sovereignty.md) — the undeployed gateway and the prompt-egress
  exposure D8 gates on.
- `openbank-libs/governance/agents.yaml` and `docs/agents/` — the fifteen existing agents and charters.
- Draft advisory GHSA-r5r5-2934-w92f — agent-service's blanket `ROLE_OPERATOR` privilege escalation.

