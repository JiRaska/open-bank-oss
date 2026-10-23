---
date: 2026-10-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [transactions, ai-agents, ml]
summary: "Two later ADR-0348 consumers: spending categories for booked transactions as a read-model attribute, and a typed intent router in front of the copilot's LLM; neither changes a booking, a balance or what a tool is allowed to do."
---

# ADR-0351 — Transaction categorisation and copilot intent routing with typed-decision models

## Context

[ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md)
names two further high-volume closed-output decisions besides complaint intake (ADR-0349) and
sanctions ordering (ADR-0350):

- **Spending categories.** The financial-health loyalty programme
  ([ADR-0282](0282-lipa-loyalty-ecosystem-financial-health-rewards-personal-offerings.md)) and the
  customer copilot ([ADR-0089](0089-customer-facing-ai-assistant.md)) both need to know what a
  transaction was *for* (groceries, rent, utilities...). No service assigns one today. The rail and
  instruction type of ADR-0103 say how money moved, not why.
- **Copilot intent.** ADR-0089 decided that "the model only routes and narrates, every figure comes
  from a tool call". Today the routing half is done by the same generative call that narrates, so
  every utterance costs a full LLM call before the right tool is even chosen.

These are lower-risk than either earlier consumer and come later in the sequence; this ADR records
the boundaries now so that the first PR for either does not have to re-derive them.

## Decision

**D1 — Spending category is a read-model attribute, never a booking fact.** A typed-decision model
assigns `SpendingCategory` (a closed enum owned by the consuming read model, versioned per
ADR-0348 D2) from the counterparty, the remittance information and the amount sign. The category is
stored beside the transaction in the consuming read model (Customer 360 / loyalty), **not** in the
ledger, the transaction journal or any outbox event that other services treat as authoritative. A
customer correction overrides it and becomes a label. Thresholds: `AUTO` once calibrated per
ADR-0348 D7; below that the transaction shows as uncategorised, never as a guess.

**D2 — The intent router selects, the charter permits.** In copilot-service a typed-decision call
maps the utterance onto `CopilotIntent`, a closed enum of the tool families the charter already
allows, plus `UNSUPPORTED` and `HANDOFF_TO_HUMAN`. A confident intent dispatches directly to its tool
path and the LLM only narrates the result. An uncertain or abstained intent falls through to today's
LLM routing. The router can **only choose among tools the OPA-filtered `tools/list` already returns
for the caller** (ADR-0225). An intent whose tool the caller may not call resolves to `UNSUPPORTED`,
so the router can never widen what a customer can reach.

**D3 — Neither decision can move money or change state.** Categories are labels on a read model;
intents dispatch to read tools or to the existing HITL write-proposal flow, which keeps its own SCA
and confirmation. A typed decision is never the confirmation.

**D4 — Residency.** Remittance information, counterparty names and customer utterances are
ADR-0175 class 1, so outside the sandbox both adapters must be `self-hosted` or EU-resident.

## Alternatives considered

- **Merchant-code and rule-based categorisation.** Deterministic and cheap. Rejected as the whole
  answer: most transactions here are account-to-account transfers with free-text remittance and no
  merchant code. Kept as the floor: a known counterparty mapping wins over the model.
- **Keep routing inside the generative call.** No new component. Rejected on cost and latency, which
  scale with every utterance, and because a typed router makes the routing auditable as a
  `DecisionRecord`, which a free-text tool choice is not.
- **Store the category on the ledger journal.** One place for everyone to read it. Rejected: a model
  estimate in the golden source (ADR-0039) would be treated as a booked fact by every consumer, and a
  re-categorisation would become a ledger change.

## Consequences

**Positive**
- Loyalty and copilot get a category signal without touching a money-path service.
- Copilot latency and LLM spend drop on the confident share of utterances, measurable from the
  `DecisionRecord` band counts.

**Negative**
- Two more consumers of the same third-party surface; each needs its own calibration eval, because a
  calibrated category model says nothing about intent calibration.

**Neutral**
- Sequenced after ADR-0349 so the port, the `TRIAGE` decision class and the evals wiring exist first.
- Gate: `typed-decision-thresholds` (ADR-0348) covers both.

### Delivery check

```bash
git grep -n 'enum class SpendingCategory' -- 'openbank-*/src/main/**'           # expect 1
git grep -n 'enum class CopilotIntent' -- 'openbank-copilot-service/src/main/**' # expect 1
# the category never reaches the ledger or the transaction journal
git grep -n 'SpendingCategory' -- 'openbank-ledger-service/**' 'openbank-transaction-service/**' # expect none
```

## Compliance impact

- PCI DSS: not applicable; the categoriser reads remittance text and counterparty, not card data.
- DORA: both adapters are non-critical; an outage means uncategorised transactions and today's LLM
  routing.
- GDPR: spending categories are profiling of a data subject's behaviour; ADR-0282's consent and
  purpose limits govern their use, and D4 binds the adapters to ADR-0175 class 1.
- PSD2: not applicable; no payment initiation or account-information change.
- CNB: not applicable; no reporting or supervised process consumes either output.

## References

- [ADR-0348](0348-typed-decision-models-a-third-inference-class-for-calibrated-schema-bound-triage-decisions.md) — the class
- [ADR-0349](0349-complaint-intake-triage-with-a-typed-decision-model.md), [ADR-0350](0350-sanctions-review-queue-ordering-with-a-typed-decision-model.md) — the earlier consumers
- [ADR-0089](0089-customer-facing-ai-assistant.md) — "the model only routes and narrates"
- [ADR-0225](0225-mcp-policy-filtered-tools-list.md) — the router can only pick from the filtered tool list
- [ADR-0282](0282-lipa-loyalty-ecosystem-financial-health-rewards-personal-offerings.md) — loyalty consumer of categories
- [ADR-0039](0039-ledger-as-golden-source-balance-as-projection.md) — why the category stays off the ledger
