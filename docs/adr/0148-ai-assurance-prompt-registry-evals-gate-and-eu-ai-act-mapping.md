---
date: 2026-07-02
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, governance, compliance]
summary: "Add an AI assurance layer: an in-repo versioned prompt registry that every prompt_hash must resolve against, a per-charter evals gate blocking model or prompt promotion on regression, and a generated EU AI Act mapping."
---

# ADR-0148 — AI assurance — prompt registry, evals gate, and EU AI Act mapping

> **Delivery note (2026-07-23) — partial.** Two of the three artifacts have a first
> increment: (1) the **EU AI Act mapping** — `docs/compliance/eu-ai-act.md` is generated
> from `agents.yaml` by `.github/scripts/gen-eu-ai-act.py` and drift-guarded by
> `check-eu-ai-act.sh`; it records the platform position (no production high-risk system;
> credit decisioning ADR-0142 would be the first) and the Art. 9–15 coverage. (2) The
> **prompt registry** exists at `openbank-libs/governance/prompts/` with the scheme and the
> first real ops-agent prompts extracted. Still open: wiring each service to *load* its
> system prompt from the registry (so `prompt_hash` resolves), the `check-prompt-registry`
> guard, and the per-charter **evals gate** — tracked as the ADR-0148 code follow-up.
>
> **Update (2026-07-25, issue #1918).** The mapping's system inventory now covers the AI
> systems that are **not** LLM agent charters — the fraud scoring plane (ADR-0084) and the ML
> decisioning substrate (ADR-0139/0140/0141/0142) — from a new
> `openbank-libs/governance/ml-systems.yaml`, kept separate from `agents.yaml` (which is hashed
> verbatim into ~29 OPA bundles) and consumed only by `gen-eu-ai-act.py`. This closes the
> deadline-critical (2026-08-02) inventory gap: the document now names every AI system, records
> that the only high-risk one (credit decisioning, ADR-0142) is *planned and unbuilt*, and that
> the nearest-to-money ML system (fraud) runs **shadow-only**. The evals-gate registry + guard
> landed in #2070; the prompt-loading wiring remains the deferred code follow-up.

## Context

ADR-0031 shipped agent identity, policy-gated MCP, human-in-the-loop
approval, and AI-attributed audit — every `AuditEvent` for an agent action
already carries `model_id`, `model_version`, and `prompt_hash`. What that
audit trail cannot yet do is answer "what did the prompt behind this hash
actually say, and has this agent's behavior been validated before this
model/prompt combination went live." There is no registry mapping
`prompt_hash` to prompt content, no evals suite gating a model or prompt
change before deployment, and no single document mapping the `agents.yaml`
charters (`compliance-officer`, `ledger-domain-engineer`, `ui-assistant`,
`rca-investigator`, `customer-copilot`, `finops-agent`, `devops-agent`) to
EU AI Act obligations. This last gap gets materially worse the moment
ADR-0142 (credit decisioning) moves past "Proposed" — creditworthiness
assessment is Annex III(5)(b) high-risk under the AI Act, which brings
mandatory obligations (Art. 9–15: risk management, data governance,
technical documentation, human oversight, accuracy/robustness) that the
current governance substrate satisfies in spirit (HITL, audit, kill-switch)
but has never mapped article-by-article.

This ADR deliberately does **not** re-decide agent governance
(ADR-0031), model provenance (ADR-0141), or the ML decisioning platform
(ADR-0139/0140/0142). It is the assurance layer sitting on top of all four:
the thing that makes a past agent decision reproducible and a future model/
prompt change measurable, before it ships.

## Decision

We will add three concrete artifacts, none of which require a new
governance primitive — they consume what ADR-0031/0141 already produce:

1. **Prompt registry.** Every system prompt used by an agent or the
   copilot lives as a versioned file under
   `openbank-libs/governance/prompts/<agent>/<version>.md`; `prompt_hash`
   in the audit event is the content hash of this file. A CI check rejects
   any agent/copilot deploy referencing a `prompt_hash` not present in the
   registry — an unregistered prompt cannot go live.
2. **Evals gate.** Each charter in `agents.yaml` declares a small set of
   scenario-based success criteria (e.g., for `finops-agent`: "flags a
   synthetic NAT-egress-spike fixture within its detection window";
   for `customer-copilot`: "never proposes a payment above the declared
   dynamic-linking scope"). A new model or prompt version must pass the
   existing eval suite before promotion; a regression against the prior
   version's pass rate blocks deploy the same way a coverage-ratchet
   regression blocks a service release (ADR-0020's pattern, applied to
   agents instead of code coverage).
3. **EU AI Act mapping.** `docs/compliance/eu-ai-act.md`, generated (not
   hand-maintained, per rule #7) from `agents.yaml`: for each charter, its
   Annex III risk classification, and which Art. 9–15 obligation each
   existing control (HITL queue, kill-switch, audit trail, OPA policy gate,
   SPIFFE identity) already satisfies versus which remains open. This
   becomes the concrete precondition ADR-0142 (credit decisioning) must
   reference before it can move past "Proposed."

## Alternatives considered

- **Store prompts in Langfuse (the SaaS observability tool declared in
  `agents.yaml` but not yet deployed) instead of in-repo.** Rejected for
  money-path-adjacent agents — a prompt controlling a `propose_payment`
  tool-call belongs inside the same audit chain and git history as the
  code it governs, not in a third-party system outside the ADR-0086
  tamper-evident chain. Non-money-path agents may still use Langfuse for
  tracing; the registry is specifically about the immutable versioned
  content, not the observability layer.
- **Defer evals until real (non-mock) models are wired up for
  finops-agent/devops-agent.** Rejected — an eval suite written after the
  first real model ships tends never to get written, because nothing forces
  it. Writing the gate now, against the mock provider, means the first real
  model swap is the first thing the gate has ever exercised, not an
  untested leap.
- **Fold the EU AI Act mapping into ADR-0031 as an amendment.** Rejected —
  ADR-0031 is already broad (agents-as-code, MCP, HITL, audit); appending a
  regulatory mapping to it every time a new agent charter is added would
  make it grow without bound. A generated, separate document that is
  regenerated whenever `agents.yaml` changes is more honest about being
  derived data.

## Consequences

**Positive**
- Makes a past agent decision reproducible: `prompt_hash` in an audit
  record now resolves to actual content, closing a real gap this platform
  review found in the audit chain.
- Gives ADR-0142 (credit decisioning) a concrete, checkable precondition
  instead of an implicit expectation that "the governance framework covers
  it."
- The evals gate is the first thing that would have caught a bad prompt
  change before it reached a `customer-copilot` deploy, rather than after.

**Negative**
- Writing meaningful eval scenarios per charter is real work, not
  boilerplate — a shallow eval suite (e.g. one trivial fixture) would
  satisfy the gate's letter without its purpose. This ADR does not by
  itself guarantee eval quality, only that a gate exists.
- Adds a new required directory/registry that must be kept in sync with
  `agents.yaml`; a charter added without a corresponding prompt-registry
  entry needs its own guard (tracked as an implementation detail, not a
  new decision).

**Neutral**
- Does not change any charter's `tools.allow`/`data_scope`/`requires_human`
  semantics — purely additive assurance around the existing model.

## Compliance impact

- PCI DSS: not applicable directly.
- DORA: Art. 9 (ICT risk management applied to AI-driven change).
- GDPR: Art. 22 (automated individual decision-making) — directly relevant
  once ADR-0142 lands; this ADR is a precondition for that ADR's own
  compliance-impact section to be honest.
- PSD2: not applicable directly.
- CNB: not applicable directly. Additionally: EU AI Act (Regulation (EU)
  2024/1689) Art. 6/9–15 and Annex III(5)(b) — the primary regulation this
  ADR maps against; `docs/compliance/eu-ai-act.md` is the artifact of
  record.

## References

- ADR-0031 (AI agent governance and operations) — the primitives (charter
  registry, OPA gate, HITL, audit) this ADR adds assurance on top of.
- ADR-0141 (model registry & provenance) — the ML-model-card equivalent for
  trained models; this ADR's prompt registry is the analogous artifact for
  prompts.
- ADR-0142 (credit decisioning engine) — the concrete future ADR this one
  is a precondition for.
- ADR-0089 (customer-facing AI assistant) — `customer-copilot` charter,
  cited as an evals example.
- ADR-0112 / ADR-0119 (FinOps agent / DevOps agent) — cited as evals
  examples.
- ADR-0020 (code coverage regression floor) — the ratchet pattern this ADR
  reuses for eval pass rates.
- `openbank-libs/governance/agents.yaml`.
