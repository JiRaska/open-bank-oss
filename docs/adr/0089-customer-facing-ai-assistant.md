---
date: 2026-06-14
decision-status: accepted
delivery-status: partial
authors: [@JiRaska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, mobile-app, customer-edge, authz]
summary: "A new openbank-copilot-service behind the customer edge is the customer-facing AI assistant; the model only routes and narrates, every figure comes from a tool call, and it never holds more privilege than the customer."
---

# ADR-0089 — Customer-Facing AI Assistant (Mobile Copilot)

**Relates to:** ADR-0031 (AI agent governance — reused primitives), ADR-0034 (unified OPA authz), ADR-0021, 0073 (SCA & device-bound credentials), ADR-0064 (customer app KMP), ADR-0065 (customer edge), ADR-0067 (feature flags & four-eyes on money-path flips), ADR-0030 (threat-model requirement), ADR-0027 (cloud substrate, FinOps), ADR-0002 (hexagonal), ADR-0048 (two version axes), ADR-0077 (DomainMetrics), ADR-0086 (payment non-repudiation & audit chain), ADR-0084 (fraud), ADR-0019 (Docs-as-Service — RAG corpus)

---

## Context

The customer mobile app (ADR-0064, KMP + Compose, talking to the bank through the customer edge,
ADR-0065) has no conversational surface. `openbank-agent-service` exists, but it is an **internal /
admin** capability: an MCP endpoint plus governance plumbing (ModelGateway, OPA `AgentPolicyGate`,
`KillSwitchService`, `CharterRegistry`) for **read-only operations driven from the admin-UI** (ADR-0031).
It holds no customer scope and is not exposed to retail clients.

A customer-facing AI assistant is a different trust domain:

| Force | Why it matters |
|-------|----------------|
| **Untrusted input is everywhere.** | Transaction memos, merchant names, uploaded documents are attacker-controllable. A customer assistant reads them — prompt injection is a first-class threat, not an edge case. |
| **It can be asked to move money.** | The product value is "do it for me" (pay, freeze a card, raise a dispute). That puts the surface on the **money path**. |
| **It speaks Czech to real customers.** | Quality and grounding are user-visible; a wrong balance or a hallucinated fee is a regulatory and trust failure, not a UX nit. |
| **FinOps.** | Self-hosting a capable model is GPU-expensive (ADR-0027). We will not pay for that until there is a business signal. |

We already own the security machinery (ModelGateway seam, OPA gate, kill switch, AI-attributed audit
from ADR-0031; unified authz from ADR-0034; SCA with dynamic linking from ADR-0021/0073). The gap is a
**customer-scoped surface** that reuses them under a stricter trust model.

## Decision

We will build a new money-path service, **`openbank-copilot-service`** (package `com.openbank.copilot`,
component `copilot-service`), as the customer-facing AI assistant, governed by one principle:

> **The model proposes; the bank disposes. The assistant never holds more privilege than the
> authenticated customer — it holds less. Every figure shown on screen comes from a tool call, never
> from model generation.**

The model is a **router + narrator**, never an authority over data or money.

### D1 — Placement & flow

`openbank-copilot-service` is hexagonal (ADR-0002), holds no banking data of its own (so it is
money-path by *capability*, not by *storage*), and sits behind the customer edge (ADR-0065). The KMP app
streams the conversation (SSE); **the LLM is never called from the device** — no keys, no prompt, no tool
schema on the handset.

```
KMP app (chat UI) ──SSE, customer session token──▶ openbank-copilot-service
                                                      ├─ ModelGateway      (reuse, ADR-0031)
                                                      ├─ OPA PolicyGate    (ADR-0034, deny-by-default, customer scope)
                                                      ├─ PromptInjection guard + PII minimiser
                                                      ├─ Grounding / RAG over bank docs (ADR-0019)
                                                      └─ MCP tool layer (on-behalf-of, audience-scoped token)
                                                           ├─ READ tools   → balance / tx / statements / FX / card  (own data only)
                                                           └─ ACTION tools → payment / card-freeze / dispute  (PROPOSAL → HITL + SCA)
                                                      ▼
                                            existing services (ledger, sepa/domestic-payment, card, dispute,
                                            fraud ADR-0084) — each RE-ENFORCES ownership (defence in depth)
```

### D2 — Money never moves on the model's word (two planes)

Action tools **cannot execute** state changes. An action tool only emits a **structured proposal**
validated against a strict schema. Execution requires both:

1. **Human-in-the-loop** — the proposal renders as an action card the model does **not** control
   (exact amount, payee, fee), and
2. **SCA with dynamic linking** (ADR-0021/0073) — the customer confirms with a device-bound credential
   whose assertion is cryptographically bound to `amount` + `payee`.

Only then does execution proceed, through the **existing money-path services** with their own
idempotency and the non-repudiation audit chain (ADR-0086). Action tools are a **closed whitelist**:
each has its own OPA policy, mandatory SCA, and idempotency key. Anything not on the whitelist is
physically uninvokable by the model.

### D3 — Untrusted content is data, never instructions

- Tool outputs and document text are **sandboxed**; the system prompt is locked and never concatenated
  with attacker-controllable strings as if they were instructions.
- No tool fires from model free-text alone: every `tools/call` passes the OPA gate (ADR-0034,
  deny-by-default) **and** schema validation before it runs.
- Model output that drives an action is **constrained / grammar-enforced JSON**, validated before it is
  treated as a proposal. A hallucinated or injected tool call is stopped by OPA + schema + (for actions)
  SCA — three independent gates.

### D4 — Grounding: no hallucinated numbers

Financial figures (balances, history, fees) are rendered by the UI from the **tool result**; the model
only narrates them. "How do I …" questions are answered by **RAG over the bank's own help / product docs
(ADR-0019)** with citations — never from the model's parametric memory.

### D5 — Least-privilege identity

`copilot-service` exchanges the customer session for an **audience-scoped, short-TTL** on-behalf-of token
that can reach **only that customer's data**. Every downstream still enforces ownership independently.

### D6 — Model strategy: one seam, environment-specific backend (FinOps)

The `ModelGateway` / LiteLLM seam is constant; only what sits behind it changes by environment, driven by
config (`sensitivity:` field already exists in `ModelGatewayConfig`):

| Environment | Behind the gateway | Rationale |
|-------------|--------------------|-----------|
| **Sandbox** | **Public free model** via the existing `OpenAiCompatibleModelProvider` (e.g. Groq / OpenRouter free tier) | Sandbox carries **synthetic data only** → no residency/retention exposure; zero GPU cost. |
| **Production** | **Self-hosted OSS model (vLLM/LiteLLM in-cluster)** — **deferred** | FinOps (ADR-0027): GPU NodePool is switched on only when a business signal justifies it. Until then this is a config flip, not a rewrite. |

This is enforced as a **governance invariant, not a code path**: the feature flag + config guarantee that
**production traffic must hit an in-cluster or EU zero-retention model**, and **sandbox may only ever see
synthetic data**. The self-hosted production deployment (GPU NodePool, vLLM) is **out of scope for this
ADR** and tracked as a separate parked item.

### D7 — Reuse the ADR-0031 governance primitives

`ModelGateway`, OPA `PolicyGate` (ADR-0034), `KillSwitchService` (ADR-0031 D7, global + per-capability),
and AI-attributed audit (ADR-0031 D5: `model_id`, `prompt_hash`, `tool_calls[]`, `policy_decision`,
human approver + reason) are reused. The kill switch and a feature flag (ADR-0067, **off by default**)
gate rollout.

### D8 — Phasing

- **Phase 1** — read-only tools + grounded RAG, behind the flag. Lowest blast radius.
- **Phase 2** — action tools (payment / card-freeze / dispute) behind HITL + SCA (D2), money-path
  controls fully wired.

> Because v1 ships action tools (per product decision), the service is treated as **money-path from the
> outset**: a threat model (ADR-0030, `docs/threat-models/openbank-copilot-service.md`) and **two
> approvals** are required, and the `money-path` label propagates to every resulting PR.

## Alternatives considered

- **Extend `openbank-agent-service` to serve customers** — Rejected. It is admin/internal trust domain
  with no customer scope; widening it would blur the two trust boundaries and put retail traffic through
  an admin governance charter built for a different actor. A separate service keeps the blast radii apart.
- **Call the LLM from the device (on-device or direct-to-provider)** — Rejected. Leaks keys, prompt, and
  tool schema to an untrusted client; makes the kill switch and audit unenforceable; can't do
  least-privilege token exchange server-side.
- **Let the model execute actions directly (autonomous agent)** — Rejected outright for a bank. Violates
  the core principle; no amount of model quality justifies an LLM moving money without HITL + SCA.
- **Self-host the production model now** — Deferred (D6). Correct end-state for data residency, but GPU
  cost is unjustified before a business signal (ADR-0027 FinOps). The gateway seam makes it a later config
  flip.
- **Frontier model via EU region (Bedrock EU, zero-retention) in production** — Kept as a documented
  fallback breaker if self-hosted OSS quality (notably Czech) proves insufficient; not the default.

## Consequences

**Positive**
- Customer-facing conversational surface with bank-grade controls reusing proven ADR-0031/0034/0021
  machinery — little net-new security code.
- Money-path safety is structural (three independent gates), not dependent on model reliability.
- FinOps-clean: no GPU spend until justified; sandbox runs free.
- Production model swap is a config flip, not a rewrite.

**Negative / risks**
- **Czech-language quality of self-hosted OSS models** is the headline risk for production (D6). Mitigation:
  a Czech banking-dialogue **eval harness as a go-live gate**, candidate models with real CS support
  (e.g. Qwen2.5-72B-Instruct, Llama-3.3-70B), and the frontier-EU fallback breaker.
- Tool-calling reliability of OSS models is weaker than frontier → leaned on constrained decoding + hard
  schema validation; the model is never the authority, so the failure mode is "refuses / asks again",
  not "wrong action".
- New money-path service ⇒ threat model + two approvals + SCA integration cost from v1.

**Neutral**
- Adds `openbank-copilot-service` to the released-component set (`version.txt` + release-please).
- RAG corpus depends on Docs-as-Service (ADR-0019) coverage of customer-facing help content.

## Compliance impact

- **PCI DSS:** No PAN in prompts/logs; card data only via tokenised tool results. PII minimiser strips
  cardholder data before any model call.
- **DORA:** Kill switch + audit trail support incident response (Art. 17); model is a documented ICT
  dependency.
- **GDPR:** Data minimisation (D5 least-privilege scope, PII minimiser); sandbox = synthetic only;
  production = in-cluster or EU zero-retention (D6). Automated-decision safeguards: no solely-automated
  money movement (D2 HITL).
- **PSD2:** SCA with dynamic linking on every payment proposal (D2, ADR-0021/0073).
- **CNB:** Conversational financial guidance carries disclaimers; the assistant gives information, not
  regulated investment advice (guardrail to be specified in the threat model).

## Implementation status (as of acceptance)

`openbank-copilot-service` is live in sandbox (v0.4.0, released via release-please #1663).
All core decisions are implemented:

- **D1** — SSE streaming endpoint deployed behind customer edge; LLM never called from device (fix #1690).
- **D2** — Proposal sentinel emitted over SSE stream (PR #1709): the app renders the non-AI-controlled
  action card and routes the validated fields into the existing customer-edge payment + SCA
  (dynamic-linking) flow. That is the live route. **Track A — the alternative server-side exchange
  (`ProposalToken` + `POST /copilot/actions/{tokenId}/confirm`) — is NOT built** (#5900): the token
  type, its two stores and the endpoint exist, but no production code issues a token and nothing
  downstream executes a confirm, so that endpoint can only answer 404. Deferred deliberately; the
  live D2 guarantee rests on the sentinel + edge-SCA route, not on Track A.
- **D3** — OPA sidecar deployed (PR #1666, `copilot-opa-bundle.yaml`); schema validation before every tool call.
- **D4** — Financial figures served from tool results only; RAG grounding over ADR-0019 corpus.
- **D5** — Audience-scoped on-behalf-of token exchange via customer edge.
- **D6** — Sandbox: public model via `OpenAiCompatibleModelProvider` (Groq/OpenRouter); production self-hosted deferred.
- **D7** — `ModelGateway`, `KillSwitchService`, AI-attributed audit reused from ADR-0031.
- **Threat model** — `docs/threat-models/openbank-copilot-service.md` present (ADR-0030).

Remaining: production GPU NodePool / self-hosted vLLM (D6 deferred, separate tracking item).

## References

- ADR-0031 (AI agent governance) — `ModelGateway`, OPA gate, kill switch (D7), AI-attributed audit (D5)
- ADR-0034 (unified OPA authz), ADR-0021/0073 (SCA), ADR-0064/0065 (customer app & edge)
- ADR-0067 (feature flags), ADR-0030 (threat models), ADR-0086 (non-repudiation), ADR-0019 (Docs-as-Service)
- `openbank-agent-service/src/main/kotlin/com/openbank/agent/ModelGateway.kt` (reuse seam)
