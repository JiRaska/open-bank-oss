---
date: 2026-08-13
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [product-catalog, ai-agents, governance, compliance]
summary: "AI may analyse and propose catalog drafts, bundles and offer explanations, while deterministic catalog policy and human maker-checker remain the only authority for eligibility, pricing and publication."
---

# ADR-0259 — AI-assisted product-catalog authoring and offer intelligence

## Context

ADR-0257 made the product catalog an industry-neutral, effective-dated authority for published
offerings. It deliberately separates immutable commercial terms from the contextual selection of
an offering. That makes the catalog a valuable grounding source for AI-assisted authoring, bundle
design, compliance review and customer-facing explanations.

It does **not** make the catalog a decision engine for an individual customer. A language model is
non-deterministic and can be induced to follow untrusted instructions in catalogue descriptions,
supporting evidence or retrieved documents. It must not set a price, decide eligibility, disclose a
private offer, publish a revision or modify a customer record. Those actions affect regulated terms
or a person and remain subject to the deterministic policy, audit and maker-checker controls in
ADR-0257. ADR-0031 already supplies a governed agent substrate: deny-by-default OPA tooling,
bounded charters, model attribution, audit, a kill switch and a proposal queue.

We need a reusable AI boundary that works for a bank, insurer or other single-tenant catalog
deployment without adding industry fields or customer data to the kernel.

## Decision

We will use AI as a **proposal and explanation layer above the catalog**, not as a catalog
authority. The deterministic catalog kernel remains the source of truth for schemas, lifecycle,
market context, effective dates, price components, eligibility predicates and published content.

### 1. Three bounded agent roles

The deployment may enable these independent, least-privilege control-plane charters. Each is
proposal-only and can use only `query.catalog.readonly` plus `draft.ticket`; none receives a
catalog write capability.

| Role | Useful work | Grounding allowed | Output |
| --- | --- | --- | --- |
| Catalog Author | Turn a human brief into a schema-conformant draft, suggested bundle relationships, localized copy and missing-information questions. | Exact trusted schema, chosen draft snapshot, approved terminology. | A proposed replacement patch and rationale. |
| Catalog Reviewer | Detect schema, lifecycle, pricing, effective-date, document and change-impact risks before a human submits a draft for publication. | Draft/current published revision, schema, deterministic validation findings and linked approved documents. | Ordered findings, evidence links and a review proposal. |
| Offer Explainer | Explain why a deterministic selector returned a published offering and describe its terms in the caller's locale. | Selector's already-authorized result, published revision and machine-readable explanation trace. | Natural-language explanation only. |

The supplied model may not call `POST`, `PUT`, `publish`, `retire`, account, customer, pricing or
entitlement APIs. The existing `draft.ticket` proposal is the only permitted non-read action; a
different human approves or rejects it, and approval still has no direct catalog side effect.

### 2. Authoring-review flow

The first delivery is a Catalog Author plus Catalog Reviewer vertical slice. A Studio operator
chooses an existing draft and explicitly starts a review. The adapter creates a bounded,
immutable review context containing the revision id, ETag/content hash, schema reference, locale,
market context, deterministic validation result and the minimal draft fields necessary for review.
It labels all product prose and linked evidence as untrusted data.

The agent returns structured findings rather than free-form executable instructions:

```
{ severity, category, instancePath, evidence, recommendation, confidence, requiresHumanDecision }
```

The server rejects any response that has an unknown field, more than the configured finding limit,
or a mutation outside the submitted revision/schema. The result becomes an AI-attributed proposal
with its context hash, schema hash, model identifier, prompt version, correlation id and a link to
the immutable revision. A human can copy a proposed patch into a draft, but the normal schema
validation, optimistic concurrency, audit/outbox and maker-checker publication flow run again.

The deterministic preflight runs before the model. Invalid schema, stale ETag, illegal price,
overlapping effective dates or incomplete mandatory content is reported directly and never handed
to a model as a request to work around it.

### 3. Bundling, private offers and hyperpersonalization

AI is useful in these areas only at carefully chosen seams:

- **Bundling.** The Author can propose `BUNDLE`, `ADD_ON`, `REPLACEMENT` or compatibility
  relationships and explain coverage gaps or duplicate fees. The kernel validates relationship
  shape, price intervals and the published revision; a human author chooses the bundle. An AI must
  not invent a discount or infer a legal price.
- **Private offers.** A private offering remains a normal published offering selected by a
  deterministic, auditable entitlement or segment rule outside the LLM. The catalog stores no
  customer list or free-text secret condition. AI may review whether an offer's market context and
  disclosure copy are internally consistent, but never discover, enumerate or reveal who is
  entitled to it.
- **Hyperpersonalization.** A separate decisioning service may rank only the already-authorized,
  published candidates returned by the deterministic selector. Its input/output evidence must be
  retained independently: consent/purpose, feature set version, model version, candidate set,
  rank, reason code and decision time. The catalog receives no per-person copies, scores or model
  features. An AI explainer can translate these recorded reason codes but cannot create them.

This keeps the product catalog portable: an insurer can use the same draft/review flow without a
banking type, while the insurer's regulated eligibility and pricing engine remains its own bounded
context.

### 4. Data, safety and operating controls

- Inputs contain product and policy data only. Customer identifiers, profiles, transactions,
  creditworthiness, claims, health information, PANs, secrets and raw support conversations are
  prohibited. Data classification and masking happen before model invocation.
- Prompt templates are versioned classpath assets. The model receives a fixed instruction/data
  boundary and no tools other than the explicitly approved read/proposal capabilities.
- Every invocation is rate- and token-limited, kill-switchable, traced and audited under
  ADR-0031. Model output is retained only under the deployment's approved retention policy; hashes
  and decision evidence remain with the catalog proposal/audit record.
- Evaluation is mandatory before a charter gains a new capability: a versioned adversarial corpus
  must cover prompt injection in product text, false regulatory claims, unsupported schema fields,
  private-offer disclosure, fabricated price/fee recommendations and a refusal to mutate or
  publish.
- A degraded model, unavailable model, failed guardrail or failed policy check is a visible
  `review unavailable` outcome. It never falls back to an ungoverned direct call or a silent
  success.

### 5. Delivery sequence and stop line

1. **P6a — Author/Reviewer:** read-only grounded context, structured proposal, audit, kill switch,
   reviewer Studio surface and adversarial/e2e tests. No automatic patch application.
2. **P6b — Bundle intelligence:** constrained relationship and copy suggestions plus deterministic
   impact simulation against catalog fixtures. No generated discounts or executable rules.
3. **P6c — Offer explanations:** natural-language explanation over a deterministic selector trace,
   with a proof that an unentitled/private offer cannot enter the prompt or response.
4. **P6d — Personalization integration:** only after the owning decisioning service demonstrates
   consent, fairness, reason-code and monitoring controls. The catalog contract remains
   customer-free.

Do not add autonomous publication, dynamic pricing, credit/underwriting/claims decisions, free-form
rule execution, model-written schemas, runtime pack upload, per-customer catalog rows, vector search
over unclassified documents or a multi-agent swarm until a named acceptance test requires it and a
superseding decision addresses the risk.

## Alternatives considered

- **Let the agent edit and publish a draft directly.** Rejected: an apparently small copy or price
  change can alter regulated commercial terms. It defeats the catalog's schema, concurrency and
  maker-checker boundaries.
- **Put customer segmentation and model scores in catalog attributes.** Rejected: it couples a
  reusable product definition to personal data, breaks portability and obscures consent/purpose
  controls.
- **Start with a recommendation model for revenue optimisation.** Rejected: it needs historical
  outcomes, experimentation, fairness evaluation and decision monitoring before it creates more
  value than risk. Grounded author/reviewer assistance has a smaller blast radius and useful human
  feedback loop.
- **Build a generic agent framework in the catalog service.** Rejected: ADR-0031 already provides
  the policy, audit, proposal, model-gateway and kill-switch substrate. A second framework would
  fork the controls.

## Consequences

**Positive**

- Product teams get faster authoring, consistent localized copy and earlier change-impact review.
- Bundles and contextual offers remain explicit, explainable catalog structures instead of opaque
  prompts.
- Banks, insurers and other deployments reuse the same safe seam without sharing data or gaining a
  banking dependency.

**Negative**

- AI output is advisory and may be unavailable, incomplete or wrong; human review and deterministic
  validation add deliberate friction.
- A deployment must operate model-provider, prompt, evaluation, retention and monitoring controls
  before enabling a charter.

**Neutral**

- Catalog v2 remains the authoritative API and v1 banking compatibility remains unchanged.
- Personalization is an integration concern, not a new catalog aggregate or tenant dimension.

## Compliance impact

- PCI DSS: not applicable — no cardholder data may enter the AI context.
- DORA: agent proposal, trace and audit evidence support controlled change reconstruction; the
  model provider and guardrail path remain an ICT dependency to assess per deployment.
- GDPR: customer data is excluded from the author/reviewer context; a future personalization
  integration must separately establish purpose, consent and retention.
- PSD2: not applicable to authoring/review; neither agent may initiate a payment or access account
  data.
- CNB: human approval, deterministic publication controls and retained evidence support supervised
  changes to customer product terms.

## References

- ADR-0031 — AI agent governance and operations
- ADR-0048 — API contract versioning
- ADR-0105 — canonical product identity
- ADR-0152 — single-tenant deployment boundary
- ADR-0257 — industry-neutral product catalog kernel and standalone distribution
- ADR-0258 — trusted JSON Schema profile for industry packs
