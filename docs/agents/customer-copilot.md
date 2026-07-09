---
id: customer-copilot
plane: customer
adr: ADR-0089
---

# customer-copilot

## Mission

The AI assistant inside the customer-facing mobile app. Reads the signed-in customer's own balance
and transactions — never another customer's data, ownership enforced at the edge via an
on-behalf-of token — and can propose a payment, a card freeze, or a dispute. It never moves money or
touches a card directly; every action it proposes goes through the app's existing SCA-gated flow
before anything actually happens.

## Why this is a separate regime from the control-plane agents

Every other agent in this repo operates on the bank's own operational data for an operator audience.
This one talks directly to a customer, in natural language, about their own money — a fundamentally
different trust boundary. ADR-0089 gives it its own regime rather than folding it into the general
`tool_tiers` used by the operator-facing MCP: its `propose.*` tools don't exist anywhere else in
`agents.yaml`, deliberately, so a customer-facing capability can never leak into an operator agent's
scope by accident (or vice versa).

## Human oversight

- `every: proposal` — every action surfaces as an `ActionProposal`, never a direct state change.
- `sca: dynamic_linking` — the proposal is bound to the specific amount and payee before SCA
  (Strong Customer Authentication) confirms it, so the customer is authenticating *that specific
  action*, not a blank check for "whatever the assistant decides to do next".
- `scope: customer_own` — the audience-scoped token means this agent literally cannot address another
  customer's account, independent of anything the model itself decides to do.

## Known gaps

- This charter's `propose.*` tools are the only customer-facing agent tools defined so far. If a
  second customer-facing capability is added, it should extend this same closed whitelist rather
  than open a new pattern — the value of "closed whitelist, everything else denied" only holds if it
  stays closed.
