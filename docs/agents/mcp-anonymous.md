---
id: mcp-anonymous
plane: customer
adr: ADR-0181
---

# mcp-anonymous

## Mission

The acting identity of the first-party **Model Context Protocol server** (`openbank-mcp-service`,
ADR-0181). The MCP server exposes a small, curated, PSD2-consent-scoped tool set to governed AI
agents: list the accounts a consent grants, read a granted account's balance and recent
transactions, list the consents held, and *propose* a payment. Every read is bounded by the
presented PSD2 consent; the one action tool (`propose.payment`) produces a reviewable proposal that
a human plus SCA dispose of — money never moves on the model's word.

## Why the identity is called `mcp-anonymous`

This is a **phase-1 placeholder**. `McpEndpoint.resolveContext()` currently sets a single fixed
principal (`agent:mcp-anonymous`) for every `tools/call`, because the OAuth 2.1 → PSD2-consent
binding (ADR-0126) is phase-2 work. The shared ADR-0034 policy plane classifies that principal as
`AI_AGENT` and `rest.rego`'s agent bridge trims the `agent:` prefix, so this charter's `id` must
stay `mcp-anonymous` exactly — otherwise the deny-by-default gate never finds a matching charter and
every tool call is refused.

When phase 2 lands, each real caller authenticates with its own audience-scoped token bound to a
specific consent, gets its own charter, and this placeholder is retired.

## Why it authorizes through the shared PDP, not agent-service's

Unlike `agent-service` (which runs its own in-service policy gate against `data.openbank.agents`),
the MCP server queries the **shared** `data.openbank.rest.allow` — the same policy plane a human
REST call is gated by. The bridge in `rest.rego` re-expresses the `AI_AGENT` call as an
`agents.allow` charter check, so an MCP tool call and a REST call are held to one policy, in one
bundle, with one audit shape.

## Human oversight

- `every: proposal` — `propose.payment` is only ever a reviewable artifact; it never debits an
  account.
- `sca: dynamic_linking` — a proposal is bound to the specific amount and payee before SCA confirms
  it, so the human authenticates *that* action, not a blank cheque.
- `scope: consent_granted` — reads can never exceed the accounts/consents the presented PSD2 consent
  grants; ownership is enforced at the consent edge, not by the model.

## Known gaps

- The identity is a single fixed placeholder (see above) until the OAuth 2.1 → consent binding
  (ADR-0126) lands. Until then one charter covers every MCP caller; the per-agent grain arrives with
  the real token binding.
- `propose.payment` reuses `customer-copilot`'s closed `propose.*` whitelist (ADR-0089). A second
  MCP action capability should extend that same closed set rather than open a new pattern — the
  "closed whitelist, everything else denied" guarantee only holds while it stays closed.
