# 102. Agentic AI differentiation — tool-use banking agent, ML fraud, and LLM-assisted KYC

Date: 2026-06-19
Status: Accepted
Delivery-Status: Partial
Author(s): OpenBank platform

## Context

OpenBank's AI presence is currently a conversational copilot (ADR-0089) that proposes actions
and waits for bank-side approval. The governance substrate is already in place:

- **MCP + OPA policy gate** (ADR-0031/0034) — every AI-initiated action is intercepted by an
  OPA sidecar before execution; the policy document defines what the model is allowed to call
  and under what conditions.
- **AI-attributed audit trail** — `openbank-libs/audit` records `actor_type=AI` with the
  model ID, confidence signal, and the human-approval reference on every AI-originated
  mutation. The hash-chained log is tamper-evident.
- **Model-proposes / bank-disposes** invariant — no AI action mutates customer data without
  an explicit approval gate. This is the floor, not the ceiling.

Three capabilities are currently absent and represent the "AI differentiation gap":

1. **Tool-use banking agent.** The copilot is chat-only; it cannot autonomously call the
   banking API (query balance, initiate a pre-approved transfer, generate a statement). The
   MCP + OPA infrastructure exists but no agent is wired to it for customer-facing use.

2. **ML fraud detection.** The fraud service currently runs rule-based scoring. Rules are
   interpretable but brittle — new fraud patterns require a human to author a new rule.
   An ML scorer (gradient boosted trees or a fine-tuned embeddings model) would learn from
   the transaction history and catch novel pattern deviations rules miss.

3. **LLM-assisted KYC / document processing.** Onboarding document handling (ADR-0094) is
   currently a manual step: the operator inspects the document, reads the extracted fields,
   and approves. Vision-capable LLMs can extract, validate, and cross-reference document
   fields — name, DOB, document number, expiry, MRZ — against the party record and flag
   discrepancies before the four-eyes adjudicator sees the case. This shortens the onboarding
   funnel and is directly compatible with the EUDI-first path (identity already at eIDAS-High;
   supplementary documents are the gap case).

**Why now.** The AI governance foundation (ADR-0031) was designed to support exactly this
evolution. Without it, the capabilities above would require building the safety layer from
scratch alongside the feature — the hardest sequencing. The foundation is in place; the
features are the natural next step.

**What this ADR does not propose.** It does not propose removing the model-proposes /
bank-disposes invariant. It does not propose autonomous money movement without an approval
gate. It does not propose training a proprietary LLM — all inference runs against hosted
models (Claude API) or self-hosted OSS models (already in gitops for copilot sandbox), with
the model ID driven by environment configuration.

## Decision

**We will extend OpenBank's AI layer along three tracks, all governed by the existing
ADR-0031 policy gate and `actor_type=AI` audit trail:**

### Track A — Tool-use banking agent (agentic copilot)

Replace the current chat-only copilot with an agent that can call a curated set of
MCP-exposed banking tools:

| Tool | OPA policy | Approval gate |
|------|-----------|---------------|
| `get_balance` | read-only; any authenticated customer | none |
| `get_transactions` | read-only; date-range param | none |
| `get_statement` | read-only | none |
| `initiate_transfer` | write; amount ≤ pre-approved limit; same-owner accounts only | four-eyes OR SCA re-auth |
| `get_product_offers` | read-only | none |
| `open_support_case` | write; idempotent | none |

The agent is a Quarkus service (`openbank-copilot-service`, already scaffolded, port 8131)
using the Claude API with tool-use (`tool_choice: auto`). Every tool call is intercepted by
the OPA sidecar (ADR-0034) before dispatch. The `initiate_transfer` tool returns a
*proposal token*, not a confirmation — the customer app's SCA re-auth flow or the four-eyes
gate must be completed before the actual payment is submitted to `sepa-payment-service` /
`domestic-payment-service`. This preserves the model-proposes / bank-disposes invariant
unconditionally.

Context window management: the agent maintains a session context in Redis (RESP3, existing
Valkey deployment) keyed by `sessionId`. PII in the context is minimised — account numbers
are replaced by masked references; the full number is resolved at tool dispatch time by
`copilot-service` from the customer's authenticated session.

### Track B — ML fraud detection

A new `openbank-ml-service` (port 8138 — next free per fleet port map) provides a fraud
scoring API consumed by the existing `openbank-fraud-service` as a secondary scorer alongside
the rule engine:

```
POST /api/v1/score
  { transactionId, amount, currency, merchantCategory, deviceFingerprint,
    velocityFeatures, geolocationFeatures }
→ { score: 0.0–1.0, model: "gbm-v2", confidence: "HIGH|MEDIUM|LOW",
    topFeatures: [...] }
```

**Model choice**: gradient boosted trees (XGBoost / LightGBM) trained on the synthetic
transaction history generated by the existing `openbank-data-simulator` tooling. Synthetic
training is the only option at sandbox stage — no real customer data. The model is retrained
in a scheduled pipeline (weekly, Temporal workflow in ADR-0101) on the accumulated synthetic
data. The model artefact is stored in S3 and loaded at startup; no in-process training.

**Scorer integration**: `openbank-fraud-service` calls `openbank-ml-service` with a 200ms
timeout; on timeout or error the rule engine score is used alone (graceful degradation).
The ML score is included in the fraud event published to Kafka (`fraud.scored.v1`) and in the
admin UI fraud review panel. It is advisory — the rule engine threshold gates the block
decision; the ML score is a confidence signal that the fraud reviewer sees.

**Explainability**: `topFeatures` (SHAP values, top-5) are included in the scoring response
and stored in the fraud event. Required for DORA Art. 17 and internal model governance
documentation (model card in `docs/ml-models/fraud-scorer.md`).

**Production data transition**: when real transaction history accumulates (post-beta go-live),
the training pipeline switches to anonymised real data. The ADR covers the architecture; the
data governance process is a separate follow-up with the DPO (GDPR Art. 22 — automated
decision-making in financial services).

### Track C — LLM-assisted KYC document processing

A new `DocumentAnalysisActivity` Temporal activity (within `onboarding-service`, no new
service) calls a vision-capable model (Claude claude-sonnet-4-6 or equivalent) to:

1. Extract structured fields (name, DOB, document number, nationality, expiry, MRZ) from the
   uploaded document image.
2. Cross-reference extracted fields against the party record in `pid-service`.
3. Return a structured `DocumentAnalysisResult` with: extracted fields, confidence per field,
   discrepancy flags, and a summary for the four-eyes adjudicator.

The model does **not** make the approval decision. Its output is a structured pre-fill for the
human adjudicator's review UI — the adjudicator still clicks Approve or Reject.

Document images are passed to the API as base64 payloads over HTTPS; they are not stored by
the model provider. The `pid-service` document store (S3, encrypted at rest) is the only
persistent location. The model API call is logged (`actor_type=AI`, `capability=KYC_DOCUMENT`)
in the audit chain.

GDPR: document images are special-category data. Lawful basis is Art. 6(1)(c) (legal
obligation — AML/KYC) and Art. 9(2)(g) (substantial public interest). The DPA record of
processing activities (RoPA) must be updated when this track ships.

## Alternatives considered

- **Build a proprietary LLM.** Zero relevance for a team of this size. Rejected.

- **Rules-only fraud forever.** Low cost now; high cost when novel fraud patterns emerge and
  require manual rule authorship. Rejected as the long-term strategy; rule engine is retained
  as the primary decision gate with ML as a confidence augmentor.

- **Third-party fraud scoring SaaS (Sardine, Sift).** Faster to ship; vendor lock-in;
  customer data leaves the stack; conflicts with OSS-bank positioning. Rejected.

- **LLM makes the KYC decision.** GDPR Art. 22 bars solely automated decisions on significant
  matters. The four-eyes human remains the decision authority. Not an alternative — a hard
  constraint.

- **Extend copilot to full autonomous agent (no approval gate).** Violates the ADR-0031
  model-proposes / bank-disposes invariant and would require re-doing the threat model from
  scratch. Rejected; the proposal token + SCA gate is the principled middle ground.

## Consequences

**Positive**
- The tool-use agent converts the copilot from a feature into a differentiated product: an
  AI that can actually *do banking*, not just answer questions about it.
- ML fraud catches pattern deviations that rules miss; the SHAP explainability satisfies
  model governance requirements out of the box.
- LLM KYC pre-fill accelerates the four-eyes adjudication step — the adjudicator reviews a
  structured diff, not a raw document image; average review time is expected to drop
  significantly.
- All three tracks use the existing OPA + audit infrastructure — no new governance surface.

**Negative**
- **Track A — LLM inference latency.** Tool-call round-trips add 500–2000ms per banking
  action. Acceptable for conversational UX; unacceptable for bulk operations. Rate-limit
  tool-use sessions explicitly.
- **Track A — context window PII handling.** The session context in Valkey holds masked data,
  but the model's own context window during a session contains resolved data. The model
  provider's data processing agreement must cover this. Claude API already has a DPA suitable
  for EU financial services; verify before go-live.
- **Track B — synthetic training data quality.** A model trained on synthetic data has
  unknown transfer performance on real fraud patterns. The synthetic data must include
  realistic fraud scenarios, not just normal transactions. This is a training-data engineering
  problem, not a model-architecture problem.
- **Track B — GDPR Art. 22 exposure.** Even with the rule engine as the final gate, if the
  ML score materially influences a block decision, the customer has a right to explanation.
  The `topFeatures` field provides this; the customer-facing explanation UI is a follow-up.
- **Track C — model hallucination on documents.** A vision model can misread a field. The
  discrepancy flags and confidence scores are safety nets; the human adjudicator is the
  backstop. Never skip the four-eyes gate based on document analysis alone.
- **Three tracks in parallel is too much.** Recommendation in the Migration section below.

**Neutral**
- `openbank-copilot-service` already exists in gitops (ADR-0089); Track A extends it, does
  not create a new service.
- Model IDs remain environment-variable driven (existing pattern); swapping models requires
  only a gitops change.

### Recommended sequencing

Ship in order: **A → C → B**.

- **A first** — highest customer visibility, uses existing infrastructure (MCP/OPA/copilot
  service), lowest new code surface. Proves the tool-use loop works before committing to new
  services.
- **C second** — single Temporal activity inside an existing service; no new deployment unit.
  Directly accelerates the EUDI onboarding funnel (ADR-0094 dependency).
- **B last** — new service, new model training pipeline, data governance review, GDPR Art. 22
  process. Highest organisational overhead; deserves its own focused sprint.

## Compliance impact

- **GDPR Art. 22** — Track B (automated scoring) and Track C (document analysis) involve
  automated processing with significant effect on individuals. Mitigations: human in the loop
  for all blocking decisions; SHAP explainability; RoPA update; DPA review.
- **DORA Art. 17** — AI models are ICT systems; they fall under the ICT third-party risk
  framework. Claude API = critical ICT third-party provider; register in the ICT TPP register
  (DORA Art. 28).
- **AML/KYC (AML6D, AMLD5/6)** — Track C accelerates but does not replace CDD obligations.
  The document analysis is a *facilitation tool*; the institution remains responsible for KYC
  quality.
- **EBA guidelines on ML in credit/fraud** — Track B model must have a model card, validation
  dataset performance metrics, and a drift monitoring schedule.
- **PSD2 SCA** — Track A `initiate_transfer` tool is not itself a payment initiation; it
  produces a proposal token. SCA re-auth is the payment initiation. This preserves PSD2 SCA
  compliance.
- **PCI DSS Req. 12.8** — third-party provider (Claude API) risk assessment required.

## References

- ADR-0031 (AI agent governance — the policy gate this ADR builds on)
- ADR-0034 (OPA unified authz — sidecar intercepting every tool call)
- ADR-0067 (feature flags — each track behind a flag)
- ADR-0089 (customer AI copilot — current chat-only baseline)
- ADR-0094 (EUDI identity hub — Track C dependency)
- ADR-0101 (Temporal — Track B training pipeline)
- EBA/GL/2021/05 — EBA Guidelines on internal governance (ML model risk)
- GDPR Art. 22 — automated individual decision-making
- DORA Art. 28 — ICT third-party risk
