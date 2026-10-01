---
id: treasury-dealing-assistant
plane: control
adr: ADR-0315
---

# treasury-dealing-assistant

## Mission

Read the treasury desk's counterparty limits, positions and deals, and propose a money-market deal
as a `DRAFT` that carries its rationale and the inputs it used. It drafts; a human dealer submits
and a different human approver books.

## Why this agent exists

Before a dealer types a deal they assemble the same picture every morning: the counterparties'
quotes against the curve, each counterparty's limit headroom, and the day's position per currency.
The agent assembles that picture and turns it into a proposal the dealer can accept, change or
cancel — with the numbers it used attached, so the proposal can be checked instead of trusted.

## Human oversight

- A `DRAFT` consumes no counterparty limit and posts nothing to the ledger.
- `any_deal_submission` — only a human may submit a draft (`PENDING_APPROVAL`); the Deal aggregate
  refuses an `AI_AGENT` actor with 403 `ACTOR_NOT_PERMITTED`.
- `any_deal_booking` — booking needs a second human who is neither the creator nor the submitter
  (four-eyes, ADR-0315 D3).
- `any_limit_override` — a limit breach is overridden only by a senior approver (ADR-0315 D4).

The prohibition is enforced three times: this charter's `tools.deny` (evaluated by `agents.rego`
through `rest.rego`'s AI_AGENT bridge), RBAC on `TreasuryResource`, and the domain's human-only
transitions, which hold even with `AUTHZ_ENFORCE` off.

## What it may read, and what it can never do

Allowed: `treasury.deal.read`, `treasury.counterparty.read`, `treasury.position.read`,
`treasury.deal.draft`. Denied explicitly: submit, approve, reject, cancel, settle, mature, reverse,
override-limit and every nostro action. No customer data is in scope — interbank counterparties
and the bank's own positions only.

## Why it ships disabled

`enabled: false`. No agent runtime holds treasury tools yet and no prompt is authored. Flip it in
the PR that wires the tools and authors the prompt
(`openbank-libs/governance/prompts/registry.yaml`).

## What would make it trustworthy

Every draft stores `rationale` and `inputs` (a JSON object of the quotes, headroom and positions it
read); both are returned on the deal and captured in the audit record, so the approver checks the
proposal against what the agent saw.
