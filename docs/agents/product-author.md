---
id: product-author
plane: control
adr: ADR-0352
---

# product-author

## Mission

The "AI Author" of ADR-0352 §12. It helps a product owner draft a deposit product as Product IR v1
and checks the draft with exactly the toolkit the `obp` CLI runs: `product_lint`, `product_compile`,
`product_simulate` and `product_explain` (read-only, in-process in openbank-agent-service). When a
draft is ready it calls `product_propose`, which records a DRAFT catalog revision plan in the
admin-ui approval queue for a human to review.

## Why this agent exists

Product as Code has three authoring surfaces — the CLI, the MCP tools and the Studio form — and
they must produce the same canonical bytes for the same product. Giving the agent the same
`ProductToolkit` functions as the CLI (gate `product-ir-authoring-parity`) means an agent-authored
draft is indistinguishable from a developer-authored one, and the review a human does is the same.

## Human oversight

`requires_human: every: proposal`. `product_propose` calls no catalog endpoint; it writes a HITL
proposal only. There is no publish, sign or approve tool registered in the MCP surface at all
(`product.publish` and `product.sign` are in `tool_tiers.deny`, and a test fails the build if any
registered tool name contains those verbs). Publishing stays a four-eyes human decision in the
catalog (ADR-0259), and the publish-path signature is never reachable by an agent (ADR-0352 §4).

## Known gaps

- The Studio form path does not exist yet, so the parity gate compares the CLI and MCP surfaces
  only; the form surface is declared pending in the gate.
- An approved proposal is still executed by a human (`obp propose --execute` or the catalog UI);
  nothing turns an approval into a catalog write automatically.
