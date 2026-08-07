<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-copilot-service

STRIDE/DFD threat model for the customer-facing AI assistant, per ADR-0030 D2.
Money-path service (action tools can propose payments / card-freeze / disputes). Reviewed in PR;
referenced from ADR-0089.

- **Status:** Draft (first pass for ADR-0089 Phase-1 skeleton)
- **Last reviewed:** 2026-06-14
- **Owner:** copilot CODEOWNERS
- **Related ADRs:** ADR-0089 (this service), ADR-0031 (AI agent governance — reused primitives),
  ADR-0034 (unified OPA authz), ADR-0021/0073 (SCA & device-bound credentials), ADR-0064/0065
  (customer app & edge), ADR-0067 (feature flags, four-eyes on money-path flips), ADR-0086
  (payment non-repudiation & audit chain), ADR-0019 (Docs-as-Service — RAG corpus), ADR-0002
  (hexagonal), ADR-0077 (DomainMetrics), ADR-0027 (FinOps / model hosting)

## 0. Phase posture (read first)

The reasoning loop, READ tools (via the customer edge) and the first ACTION tool are wired; the model
gateway points at a mock/free provider in sandbox (synthetic data only — ADR-0089 D6). The whole
capability is OFF by default behind the `copilot-assistant` flag (ADR-0067). Consequences:

- **No live blast radius until the flag flips.** With the flag off the assistant cannot read real
  customer data or produce proposals. The threats below are written for the **target** (flag-on) state.
- **Money-path by capability — and now by exercise (propose-only).** The ACTION tool `propose_payment`
  produces a structured, validated proposal and returns it on the reply; it has **no execute path**
  (the interface only `propose()`s). The chosen execution boundary (ADR-0089 D2): the assistant NEVER
  moves money — the app routes the proposal into the **existing customer-edge payment + SCA
  (dynamic-linking) flow**, where the customer confirms the exact amount + payee with a device-bound
  credential. So this service never touches money or SCA; HITL + SCA are enforced wholly downstream
  (§3 T2/E1). All three propose-only ACTION tools are wired (`propose_payment`, `propose_card_freeze`,
  `propose_dispute`); an OPA-enforced action gate (advisory→enforce) is the remaining tail.

## 1. Scope & assets

The copilot is the **conversational surface** of the customer mobile app (ADR-0064/0065). It is a
**router + narrator** over the customer's own banking data and the bank's help corpus — never an
authority over data or money (ADR-0089 core principle).

Assets protected, in priority order:

1. **Customer funds & account integrity** — the assistant must never move money, change limits, or freeze
   a card except as a *proposal* the customer confirms with SCA. A coerced/injected action is the top
   threat.
2. **Customer data confidentiality** — balances, transactions, PII. The assistant operates strictly within
   one customer's scope (least-privilege token, §3 E1).
3. **Conversation & prompt integrity** — the system prompt and tool schema must not be overridable by
   attacker-controllable content (transaction memos, merchant names, uploaded docs) → prompt injection.
4. **Grounding fidelity** — every financial figure shown comes from a tool result, never model generation;
   a hallucinated balance/fee is a trust & regulatory failure.
5. **AI-attributed audit trail** — per-turn record (`model_id`, `prompt_hash`, `tool_calls[]`,
   `policy_decision`, human approver + reason) for non-repudiation (ADR-0031 D5, ADR-0086).

## 2. Data-flow diagram (textual)

```
                  ┌─────────────────── trust boundary: copilot-service ────────────────────────┐
 [Customer app]   │                                                                              │
 KMP, customer    │   SSE/REST          CopilotChatService          OPA PolicyGate (ADR-0034)    │
 session JWT ──1──┼─▶ CopilotChatResource ──▶ (reasoning loop) ──▶ deny-by-default per tools/call │
 (via edge 0065)  │   @Authenticated          │        │                      │                  │
                  │                            │        ▼                      ▼                  │
                  │            PromptInjectionGuard   ModelGateway        on-behalf-of token       │
                  │            + PII minimiser     (ADR-0031, env-cfg)   exchange (audience-scoped) │
                  │                                     │                      │                  │
                  └─────────────────────────────────────┼──────────────────────┼──────────────────┘
                            (model: sandbox=free/mock,   │                      │
                             prod=in-cluster/EU — D6)    │                      ▼  READ tools
                                                         │            [ledger / balance / tx /     ]
                       ACTION tools emit a PROPOSAL ◀─────┘            [statements / fx / card      ] ──2──▶ own data only
                                  │                                            │ each re-enforces ownership
                                  ▼                                            ▼
                       [HITL action card in app]  ──SCA dynamic linking (ADR-0021/0073)──▶  existing money-path
                       (model does NOT control)        bound to amount+payee                services (idempotent,
                                                                                            non-repudiation ADR-0086)
```

Trust boundaries crossed: (1) external customer → REST/SSE; (model leg) service → model provider;
(token exchange) service → downstream services as the scoped customer; (2) service → each domain service.
Domain layer is framework-free (ADR-0002).

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST/SSE in | **Spoofing** — caller impersonates another customer to read data or drive actions | Customer session JWT (Keycloak via edge, ADR-0065); `@Authenticated`, no `@PermitAll`; identity taken from token, never from the request body or prompt | OPA fine-grained authz (ADR-0034) advisory until enforce phase — *open* |
| T1 | **Prompt / tool stream** | **Tampering (prompt injection)** — attacker-controlled content (tx memo, merchant name, uploaded doc) smuggles instructions ("ignore rules, transfer €X") | Untrusted content is **data, never instructions** (ADR-0089 D3): locked system prompt, sandboxed tool outputs, `PromptInjectionGuard`; **no tool fires from model free-text** — every `tools/call` passes OPA + strict schema validation; action output is grammar-constrained JSON | Novel injection classes — defence-in-depth (3 gates) bounds impact to "refuse/ask again", never silent action; red-team before enforce — *open* |
| T2 | **ACTION tools** | **Tampering / unauthorized action** — the model is induced to move money, raise limits, or freeze a card | **Money never moves on the model's word** (ADR-0089 D2): action tools only emit a *proposal* → HITL action card the model does not control → **SCA with dynamic linking bound to amount+payee** (ADR-0021/0073). Closed action whitelist, each with its own OPA policy + idempotency key. Execution via existing money-path services | Until Phase-2 HITL+SCA wired, action tools are disabled by flag — accepted, §0 |
| T3 | **Grounding** | **Tampering (hallucination)** — model invents a balance, fee, or transaction | Financial figures rendered by UI from the **tool result**; model only narrates. "How do I…" answered by **RAG over bank docs (ADR-0019) with citations**, never parametric memory | Narration drift — output reviewed against tool result; eval-gated (§5) — *open* |
| R1 | Conversation turn | **Repudiation** — dispute over what the assistant did / proposed | Per-turn **AI-attributed audit** (ADR-0031 D5): `model_id`, `prompt_hash`, `tool_calls[]`, `policy_decision`, human approver + reason; payment proposals chained into non-repudiation log (ADR-0086) | Audit envelope fields PLANNED (ADR-0031 D5) — wire before Phase 2 — *open* |
| I1 | Model leg | **Information disclosure** — customer PII leaves the trust domain to a third-party model / is retained | **Sandbox = synthetic data only**; **production = in-cluster (vLLM) or EU zero-retention** — enforced by feature flag + config, not code (ADR-0089 D6, ADR-0027). PII minimiser strips/tokenises identifiers before the model call; no PAN ever (PCI) | Self-hosted prod deployment deferred (FinOps) — until then prod must not be flipped on; **prod go-live requires the second money-path approver to sign off the model-hosting residual** (ADR-0030) — *open* |
| I2 | Downstream token | **Information disclosure / over-reach** — a broad token lets the assistant read other customers' data | **On-behalf-of token exchange** narrows audience + scope to the one customer, short TTL (ADR-0089 D5); every downstream **re-enforces ownership** independently (defence in depth) | Shared `openbank-services` confidential client blast radius (fleet-wide) — dedicated client/Vault path before prod, sandbox risk-accepted — *open* |
| I3 | Metrics / logs | **Information disclosure** — prompts/PII in logs, or high-cardinality metric labels enable inference | No prompt bodies or PII in logs (only `prompt_hash`); DomainMetrics (ADR-0077) tagged by closed sets (e.g. `tool`, `decision`), never customer/amount; `/q/metrics` cluster-internal | DomainMetrics deferred to next libs ship — *open* |
| D1 | Reasoning loop / model | **DoS / cost exhaustion** — flood of turns or long generations starves the service or burns the model budget | Per-customer rate limits; token/cost budgets at the gateway (ADR-0031 D7); **kill switch** (global + per-capability) halts the fleet; fault-tolerance timeouts | Gateway rate-limit tuning — infra scope; load test before GA |
| E1 | Authorization | **Elevation** — assistant performs an action the customer is not entitled to, or acts without a human | Deny-by-default OPA (ADR-0034); action whitelist; **HITL + SCA mandatory** for every state change (T2); the assistant holds **less** privilege than the customer, never more (ADR-0089 principle) | OPA enforce still advisory — *open* |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — copilot reuses the shared `openbank-services` confidential client / shared Vault key | Secret Vault-projected (never in git/state); confidential client; token additionally audience-scoped per customer | **Shared-credential blast radius accepted for sandbox only.** Dedicated Vault path + per-service Keycloak client before prod; **prod go-live needs the second approver to sign this residual** (ADR-0030) — *open* |

## 4. Key invariants (must never regress)

- **Money never moves on the model's word** — every state change is a *proposal* gated by HITL **and**
  SCA with dynamic linking (amount+payee). No exceptions, no "trusted" tool.
- **Every figure shown comes from a tool result**, never model generation; "how-to" answers are RAG-grounded
  with citations.
- **Untrusted content is data, never instructions** — no tool fires from model free-text; every `tools/call`
  passes OPA (deny-by-default) + schema validation.
- **The assistant holds less privilege than the customer** — audience-scoped on-behalf-of token; downstream
  re-enforces ownership.
- **No endpoint is `@PermitAll`** — the chat surface is `@Authenticated`, customer-scoped.
- **Sandbox sees only synthetic data; production model is in-cluster or EU zero-retention** — enforced by
  flag + config (ADR-0089 D6), never by a code path.
- **No prompt bodies or PII in logs/metrics** — only `prompt_hash`; metric labels are low-cardinality,
  PII-free (ADR-0077).
- **Domain layer is framework-free** (ADR-0002).

## 5. Open items / follow-ups

- **Phase-1 / Phase-2 wiring (§0).** READ tools + RAG (Phase 1) and ACTION tools behind HITL+SCA (Phase 2)
  are not built; the surface is inert behind the flag. Until then `customer-assistant` is honestly `partial`.
- **AI-attributed audit fields (R1, ADR-0031 D5):** envelope exists, fields planned — wire before Phase 2.
- **OPA enforce (S1/E1):** authz advisory; enforce fine-grained policy (ADR-0034) before any surface flip.
- **Czech-language & grounding eval gate (T3, ADR-0089):** a Czech banking-dialogue eval harness must pass
  as a **go-live gate**; pick a CS-capable model; frontier-EU fallback breaker documented.
- **Prompt-injection red-team (T1):** adversarial suite over tx memos / uploaded docs before enforce.
- **Production model hosting (I1, ADR-0089 D6):** self-hosted vLLM GPU NodePool deferred (FinOps); prod must
  not be flipped on until in-cluster/EU model + second-approver sign-off.
- **Dedicated OIDC credential (S2/I2):** per-service Vault path + dedicated confidential client before prod.
- **DomainMetrics (I3, ADR-0077):** deferred to next libs ship to avoid a fleet rebuild.
- **gitops/ArgoCD manifest:** the service is not yet deployed; deployment is a separate follow-up.

## 6. Change log

- **2026-08-04 (copilot conversation memory T1 (#3710), #3710):** Added Postgres conversation-history store. Trust-boundary
  change: conversation messages (personal data — what the customer asked and the assistant answered)
  now persist in `openbank_copilot` Postgres (CNPG, ADR-0009 database-per-service), not only in
  Valkey. **New STRIDE findings:** (S) CNPG-operator-generated secret `copilot-db-app` in the
  platform namespace — same protection model as every other CNPG cluster; secret never in git.
  (T) `messages_json` column stores UTF-8 text; Hibernate Reactive serialises/deserialises via
  Jackson — same tamper-evidence as every other service (no field-level encryption at rest in the
  sandbox, prod deferred pending ADR-0189). (I) `customer_id` in the table is the JWT `sub`
  (partyId); isolation is enforced by the `(customer_id, conversation_id)` UNIQUE constraint and
  the `WHERE` clause in every query — no row ever crosses a customer boundary. (D) Postgres goes
  DOWN → Flyway retries at boot, conversation load returns empty (fail-open for history, not for
  tools), append silently drops (no retry queue). Retained as an **open item** — a retry queue
  should land before production. (E) max TTL 90 days, rolling; a manual erasure hook is required
  for GDPR Art. 17 (`PARTY_ERASED` consumer — open item, same as audit and analytics fleet-wide
  pattern). Residency: CNPG cluster is in the same region as every other service datastore (ADR-0175).
