---
id: ap2-anonymous
plane: customer
adr: ADR-0193
---

# ap2-anonymous

## Mission

The acting identity of the bank-side **AP2 mandate verifier** (`openbank-ap2-service`, ADR-0193).
The verifier exposes a single capability to governed AI agents: `verify.mandate`. Given a presented
AP2 (Agent Payments Protocol) signed mandate and the payment it is offered to authorize, it verifies
the mandate's Ed25519 signature chain **and** its constraints (payee, amount cap, currency, expiry)
and returns a verdict plus an authorization-evidence record. **It moves no funds** — a valid verdict
is an input to the SCA/payment decision, never a payment (ADR-0193).

## Why the identity is called `ap2-anonymous`

This is a **phase-1 placeholder**. `Ap2VerifyEndpoint` sets a single fixed principal
(`agent:ap2-anonymous`) when the `X-Agent-Id` header is absent, because the OAuth 2.1 agent-token
binding is phase-2 work. The shared ADR-0034 policy plane classifies that principal as `AI_AGENT`
and `rest.rego`'s agent bridge trims the `agent:` prefix, so this charter's `id` must stay
`ap2-anonymous` exactly — otherwise the deny-by-default gate never finds a matching charter and every
`/ap2/verify` is refused.

When phase 2 lands, each real caller authenticates with its own token, gets its own charter, and
this placeholder is retired.

## Why it authorizes through the shared PDP

Like `openbank-mcp-service` (and unlike `agent-service`'s in-service gate), the verifier queries the
**shared** `data.openbank.rest.allow` — the same policy plane a human REST call is gated by. The
bridge in `rest.rego` re-expresses the `AI_AGENT` call as an `agents.allow` charter check, so an AP2
verify call is held to one policy, in one bundle, with one audit shape. A PDP connectivity error
fails `/ap2/verify` **closed** (HTTP 503) — never fail-open on a payment-authorization surface.

## Human oversight

- `sca: dynamic_linking` — the verifier itself never executes. When a future ADR wires the verdict
  into the payment path, agent-initiated execution stays behind HITL + SCA dynamic-linking above a
  configurable threshold; a valid mandate *lowers* the human checkpoint, it never removes it.

## Data scope

The verifier reads nothing from the banking domain — it evaluates a presented mandate against a
presented payment. Its evidence record is data-minimised (the mandate hash + constraints, not the
full credential; ADR-0193 GDPR row).

## Known gaps

- The identity is a single fixed placeholder (see above) until the OAuth 2.1 binding lands.
- Phase 1 resolves issuer keys against a configured trust list (`AP2_TRUST_LIST`); DID / issuer-
  registry resolution is phase 2 behind the same `MandateKeyResolver` port.
- The verdict is not yet consumed by the SCA/payment path — that wiring, and the fraud-reimbursement
  liability gap it depends on, is a further ADR (ADR-0193 §3).
