---
id: business-copilot
plane: business
adr: ADR-0284
---

# business-copilot

## Mission

customer-copilot (ADR-0089) wearing the business hat. The same on-behalf-of token, now carrying the
entity the caller is acting for, and the same proposal-only action tools. "Send the invoice from the
company account" produces an `ActionProposal` that the customer edge routes into the existing
payment + SCA flow. The agent moves no money and never has.

## Why this agent exists

A person who runs a company already talks to the bank through the app; ADR-0284 gives that person a
second profile rather than a second login. Everything the retail copilot does — read my balance,
explain this transaction, propose a payment — is the same request under the business hat, against a
different party. Building a separate assistant for it would duplicate the ADR-0089 regime and its
SCA coupling, which is where the safety actually lives.

## The scope rule is the whole charter

It reads the entity the caller is **currently acting for**, and nothing else. Not every entity they
hold a mandate over, and not their personal party in the same breath. A person who runs two
companies must not see one from inside the other, and the model is never asked to pick which: the
mandate resolved at the edge decides, fail-closed (ADR-0284 D4), and the agent receives the answer.

That is also why `scope: acting_for_entity` is a `requires_human` entry rather than a comment. It is
a property of the session the platform must hold, in the same list as the SCA requirement.

## Human oversight

- `every: proposal` — an `ActionProposal` on the reply, never a direct effect.
- `sca: dynamic_linking` — HITL plus SCA bound to amount and payee (ADR-0021/0073) before any state
  change, applied by the edge flow. The agent never calls `money.*`; the deny block says so.
- `scope: acting_for_entity` — one entity per session.

## Known gaps

- **No runtime exists.** `enabled: false`. The charter lands with the design so the powers are
  bounded before the loop is written.
- **A mandate can be revoked mid-session.** The edge resolves acting-for per request (ADR-0284 D4),
  so a revoked mandate stops the next call rather than the current one — a proposal already on the
  reply is disposed under the SCA flow, which re-checks. Worth knowing rather than assuming the
  session is a transaction.
- **`pii: own` means the ENTITY's own data.** A company's transactions name counterparties who are
  natural persons; those are the company's records, and the agent's answers are scoped to the
  company, but the distinction is one an operator should keep in mind when reading an audit trail.
