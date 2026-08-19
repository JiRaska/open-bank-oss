---
date: 2026-08-19
decision-status: proposed
delivery-status: partial
followup: "#5671 — reasoning graph and pgvector retrieval are unbuilt; Langfuse ingestion has no telemetry-based evidence and no retention policy yet"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, observability, security, architecture]
summary: "Close the gap between the AI stack agents.yaml declares and the one that runs: a Kotlin reasoning graph over Temporal instead of LangGraph, Llama Guard via the gateway, self-hosted Langfuse, and pgvector retrieval."
---

# ADR-0265 — Completing the declared AI stack: reasoning, guardrails, tracing and retrieval

## Context

`agents.yaml` declares a five-layer AI stack. Four of its five layers carry an inline
`as-built:` note written by whoever last checked, and those notes say the layer is not
deployed:

| Layer | Declared | As-built (agents.yaml' own note) |
|---|---|---|
| Orchestration | `temporal` | **live** — agents are Temporal workflows |
| Reasoning | `langgraph` | NOT DEPLOYED — activities are hand-written Kotlin |
| Model gateway | (undeclared) | live — LiteLLM in `ai-platform` |
| Guardrails | `llama-guard` + injection filter | PARTIAL — the regex filter is real, Llama Guard is not |
| Memory / RAG | `pgvector` | NOT DEPLOYED — deterministic keyword scoring |
| Observability | `langfuse` | NOT DEPLOYED — OTel only |
| Identity | `spiffe-spire` | PARTIAL — OpenBao `pki-agent` CA is the live mechanism |

Two things make this worth an ADR rather than four independent tickets.

First, **the declaration is load-bearing**. `agents.yaml` is consumed by the OPA bundles,
the EU AI Act mapping (`docs/compliance/eu-ai-act.md`) and the admin-UI governance
snapshot. A declared-but-absent layer is not a documentation nit: it is a control the
compliance surface credits the platform with and that does not exist. ADR-0031 D6 has
carried the honest `partial` status for this the whole time — this ADR is the plan that
retires it.

Second, **two of the four names are wrong for this platform**, and shipping them as
declared would be worse than the gap. `langgraph` is a Python library; the fleet is
Kotlin/Quarkus with Temporal already owning durable orchestration. `spiffe-spire` names a
runtime the cluster does not run, while the identity property it stands for is already
enforced by a different mechanism. A declaration should name what runs.

## Decision

We will close the gap in four slices, and where the declared technology is wrong for the
platform we will change the DECLARATION, not the platform.

1. **Reasoning: a Kotlin reasoning graph over Temporal, not LangGraph.** Nodes, edges,
   typed state, a bounded tool-calling loop and a step cap, expressed as a small Kotlin
   DSL whose state is a Temporal workflow's state — so a run is durable, replayable and
   already inside the audit and kill-switch machinery. Adopting LangGraph would mean a
   second language runtime, a second Dockerfile, a second CI lane, a second supply-chain
   surface and a threat model, to buy a graph abstraction we can express in ~300 lines
   against an orchestrator we already run. `agents.yaml` is updated to say what runs.
2. **Guardrails: Llama Guard through the existing LiteLLM gateway.** A model-based
   `ContentSafetyPort` beside the deterministic prompt-injection filter — neither replaces
   the other. The verdict is three-valued (`SAFE` / `UNSAFE` / `UNAVAILABLE`) and
   `UNAVAILABLE` is never folded into `SAFE`, so a classifier that could not be reached is
   visible rather than reported as a clean bill of health. Served on the Groq key already
   projected into the gateway pod: no new provider, no new secret, no GPU.
3. **Observability: self-hosted Langfuse v2, fed by the gateway.** LiteLLM posts traces
   server-side, so every caller already routed through the gateway is covered with no
   application change and no second SDK in ~34 services. v2 (one container over one
   Postgres) rather than v3 (web + worker + ClickHouse + Redis + S3), which is five new
   workloads on a cluster where a single-replica rollout has already deadlocked for want
   of room to surge.
4. **Memory/RAG: pgvector on the copilot's existing CNPG Postgres**, hybrid keyword +
   vector retrieval, embeddings generated through the same governed gateway. This is
   ADR-0183's decision, executed; ADR-0183 deliberately deferred it until the corpus
   outgrew keyword search, and this ADR records that we are now building the mechanism
   rather than waiting, because the retrieval quality gap is already visible in the
   copilot's answers on the help corpus.

**Identity stays as it is and the declaration is corrected.** SPIRE is not deployed and
this ADR does not propose deploying it: the property SPIFFE stands for — short-TTL,
verifiable workload identity with proof of possession — is already enforced by the
OpenBao `pki-agent` CA plus the CN cross-check (ADR-0031 D3b), which is live and E2E
verified. Replacing a working mechanism to make a label true is the wrong trade.

## Alternatives considered

- **Adopt LangGraph literally (a Python `openbank-agent-graph` service).** Pro: the
  declaration becomes true verbatim, and the ecosystem's tooling comes with it. Con: a
  second language in a Kotlin monorepo, with its own Dockerfile, CI lane, dependency
  scanning, threat model and AGPL boundary question — and the durable-execution property
  it would need is Temporal's, which we already have, so the two would have to be
  reconciled. Rejected: cost dominated by everything other than the graph abstraction.
- **Langfuse Cloud instead of self-hosting.** Pro: zero operational surface. Con: prompts
  and completions leave the cluster to a SaaS, which is the exact egress posture ADR-0175
  exists to prevent, and ADR-0148 deliberately keeps money-path prompt content in-repo and
  in the audit chain. Rejected on data residency.
- **Langfuse v3.** Rejected for now on operational cost (see decision 3); it remains the
  upgrade path, and the data lives in Postgres either way.
- **Do nothing and fix the declaration only** (mark all four layers as "not planned").
  Pro: honest, free. Con: three of the four are real capability gaps, not naming problems
  — there is no model-based safety classifier, no stored prompt/completion evidence and no
  semantic retrieval anywhere in the platform. Rejected: only the reasoning and identity
  rows are genuinely declaration problems.
- **A dedicated vector database.** Already considered and rejected in ADR-0183; not
  reopened here.

## Consequences

**Positive**
- The compliance surface stops crediting the platform with controls it does not have.
- Prompts and completions become durable evidence, keyed by trace id, which today exists
  only as an unreadable `prompt_hash` in the audit envelope.
- A model-based classifier catches the hazards no regex enumerates, on both the input and
  the output side.
- Retrieval quality on the help corpus stops being bounded by keyword overlap.

**Negative**
- Four new operational surfaces to run and pay for: a Langfuse pod, a second CNPG cluster,
  a classifier call on the request path, and an embedding index to keep in step with its
  source markdown.
- Latency: the guardrail adds a network round trip before the model call. Bounded by a
  short timeout, and the classifier degrades to `UNAVAILABLE` rather than hanging.
- The Langfuse UI is a public origin behind application auth — a documented exception to
  ADR-0056, acceptable only while the sandbox is synthetic-data-only (see below).

**Neutral**
- Storage on the Langfuse volume grows with traffic, not with time; retention policy is a
  follow-up, not a launch blocker at sandbox volumes.

## Compliance impact

- PCI DSS: not applicable — no cardholder data flows through these components; the copilot
  data scope is PII-masked before it reaches a model (ADR-0031).
- DORA: relevant. Stored prompts/completions materially improve incident reconstruction,
  which the platform previously could not do for an AI run. Each new component is an ICT
  asset and inherits the existing third-party/model-provider posture (Groq remains the
  model provider; Langfuse is self-hosted, so it adds no third-party dependency).
- GDPR: relevant, and the sharpest edge of this ADR. Langfuse stores prompts and
  completions, which on an environment carrying real traffic would be personal data at
  rest in a new store with its own retention and access path. Two things bound it today:
  the sandbox is synthetic-data-only, and the copilot masks PII before any model call. A
  production rollout requires a retention policy, SSO in front of the UI, and an entry in
  the Art. 30 record — none of which this ADR claims to have delivered.
- PSD2: not applicable — no SCA, consent or payment-initiation path changes; agents remain
  proposal-only on the money path.
- CNB: relevant in the same direction as DORA — auditability of automated decisioning
  improves, because the evidence for what an agent was told and what it answered becomes
  retrievable rather than hashed.

## References

- ADR-0031 — AI agent governance and operations (D3 identity, D5 audit, D6 open stack, D7 observability)
- ADR-0089 — customer copilot (D3 guardrails, D6 model sensitivity)
- ADR-0148 — AI assurance: prompt registry, evals gate, EU AI Act mapping
- ADR-0174 / ADR-0175 — LLM gateway topology and egress posture
- ADR-0183 — pgvector retrieval augmentation for the copilot knowledge base
- ADR-0056 — public surface default; ADR-0092 — the precedent for a documented exception
- `openbank-libs/governance/agents.yaml` — the declaration this ADR reconciles
