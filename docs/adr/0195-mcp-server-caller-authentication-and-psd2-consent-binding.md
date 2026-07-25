---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, psd2-api, authz, sca]
summary: "MCP server becomes an OAuth 2.1 resource server: the token sub is the agent principal and its consent_id is live-validated at consent-service each call (account scope never trusted from the token), so real read ports can replace the stub."
---

# ADR-0195 — MCP server caller authentication and PSD2 consent binding

## Context

`openbank-mcp-service` (ADR-0181) exposes curated, consent-scoped PSD2 read tools plus one
HITL payment-proposal tool to governed AI agents over MCP. Its `tools/call` gate is already
authorized by the shared ADR-0034 OPA PDP as an `AI_AGENT` principal (phase-2 OPA wiring shipped
in #2142; the `rest.rego` null-resource bridge bug was fixed in #2152).

**The service authenticates nobody.** The threat model (PR #2200) established, and issue #2206
tracks, that today:

- `McpEndpoint.resolveContext()` hardcodes `ConsentContext("agent:mcp-anonymous", "none", emptyList())`
  — it does not read the `X-Agent-Id` / `X-Consent-Id` headers its own KDoc claims. OIDC is
  `tenant-enabled: false`; there is no `@RolesAllowed`, no mTLS.
- The OPA PDP therefore authorizes a **constant** principal. It works correctly; it is asked the
  wrong question. Every tool call is `agent:mcp-anonymous` with `consentId=none` and an empty
  granted-accounts set.
- Consent-scoped account filtering is delegated by `ReadPorts.kt` to "the port implementation", and
  the only implementation is `StubReadPorts`, which enforces nothing and returns a canned note.

**The stub is the load-bearing security control, and nothing else guards it.** Phase-2's stated plan
— swap `StubReadPorts` for real `@RegisterRestClient` adapters — needs no endpoint and no policy
edit. The moment a real port lands, `get_balance(accountId)` becomes an **unauthenticated read of any
guessable account id**, with every CI gate green. A regression guard now blocks exactly that
ordering (PR #2230): a non-stub port cannot be merged while the placeholder identity remains. This
ADR decides the design that removes the placeholder so the real ports can land.

Why now: the OPA plane and the guard are in place; the only thing between the MCP server and real
data is a decided, safe caller-identity + consent-binding contract. Everything it needs already
exists — `consent-service` is the single consent authority (ADR-0126), it already serves AI-agent
delegation scopes (`AGENT_QUERY`, `AGENT_INITIATE`) and a machine-to-machine
`POST /api/v1/consents/{id}/validate` that returns `{valid, scopes, grantedAccounts, frequencyPerDay}`
after checking grantee-match, `isActive`, `hasScope` and `coversAccount` (#1609, openapi 1.2.0).

## Decision

We will make `openbank-mcp-service` an **OAuth 2.1 resource server** and bind every tool call to a
live-validated PSD2/agent-delegation consent. Concretely:

1. **Enable OIDC** (`quarkus.oidc.tenant-enabled: true`) against the existing Keycloak realm. Every
   `tools/call` MUST present a `Bearer` access token; an absent/invalid token is `401`, never the
   current anonymous constant.

2. **Agent identity from the token.** `resolveContext()` reads the validated token's `sub` (shape
   `agent:<id>`, which `AuthorizeInterceptor.principalType()` already classifies as `AI_AGENT`) and
   uses it as the OPA principal id — replacing the hardcoded `agent:mcp-anonymous`. The charter that
   authorizes the call is the one matching that real id, not a shared placeholder.

3. **Consent id from the token, granted accounts from consent-service — never from the token.** The
   token carries a `consent_id` claim (and the `AGENT_QUERY` / `AGENT_INITIATE` scopes) minted when
   the consent was granted. `resolveContext()` reads `consent_id`, then calls
   `POST /api/v1/consents/{consent_id}/validate` on `consent-service` and takes `grantedAccounts` /
   `scopes` from that **live** response. The token's own claims are never trusted for account scope —
   live validation is what respects revoke/expire (ADR-0126 sweeper + outbox events) and enforces
   grantee-match (the presented `agent:<id>` MUST equal the consent's grantee, closing the
   confused-deputy hole).

4. **Real read ports enforce the intersection.** The real `@RegisterRestClient` adapters
   (account/balance/transaction/consent) receive the validated `ConsentContext` and MUST reject any
   `accountId` not in `grantedAccounts` before calling downstream — a tool never sees an account the
   consent did not grant. `propose_payment` additionally requires the `AGENT_INITIATE` scope and
   stays a HITL proposal disposed of by a human under per-transaction SCA dynamic-linking (ADR-0126
   AI-agent scope cap; ADR-0089 regime) — money never moves on the model's word.

5. **Fix the policy-input gap on the same schedule.** `rest.rego`'s `agent-charter-allows` bridge
   currently drops the `attributes` map, so the PDP never sees the `consentId` the endpoint passes
   (#2206). The bridge MUST forward `attributes` so a future policy can gate on consent state, not
   only on the capability.

**Token minting is an issuer concern, decided separately and allowed to evolve.** This ADR fixes the
*resource-server contract* the MCP server enforces (steps 1–4), which is stable regardless of how the
token is produced. The token MAY start as a standard Keycloak client grant carrying the `consent_id`
claim, and SHOULD converge on **RFC 8693 token exchange** once ADR-0177 (workload-identity M2M) lands
— the agent exchanges its platform-attested identity for a consent-scoped, audience-restricted
access token. Nothing in steps 1–4 changes when the issuer does.

**Ordering (hard constraint, #2206):** steps 1–4 land **before, or atomically with**, the first real
read-port adapter. The #2230 guard enforces this mechanically.

## Alternatives considered

- **Option A — OAuth 2.1 resource server + live consent-service validation (CHOSEN).** As above.
  Pros: reuses everything that already exists (OIDC, the `agent:` sub convention the interceptor
  already classifies, the live `/validate` endpoint with grantee-match, the `AGENT_*` scopes, the
  ADR-0126 revoke/expire propagation); live validation is the only design that honours revocation
  between token issuance and use; no new trust store. Cons: one extra in-request hop to
  consent-service per call (bounded, cacheable within `frequencyPerDay`), and it depends on tokens
  actually carrying `consent_id` — an issuer contract that must be pinned.

- **Option B — mTLS agent identity (pki-agent SVID) + consent id in a header.** The agent
  authenticates with its existing `pki-agent` client cert (`AgentSvidVerifier`, ADR-0031 D3b) and
  passes `consent_id` in a header; `consent-service /validate`'s grantee-match still binds agent to
  consent. Pros: no dependency on Keycloak minting agent-consent tokens; leans on identity plumbing
  that already exists. Cons: a header-carried `consent_id` is only as trustworthy as the transport;
  it splits identity (mTLS) from authorization (Bearer/OIDC) that the rest of the fleet standardises
  on; and it does not align with the OAuth 2.1 posture the MCP KDoc and ADR-0181 already state.
  Rejected as the primary path, but its `AgentSvidVerifier` remains a viable defense-in-depth layer
  under Option A.

- **Option C — RFC 8693 token exchange as a hard prerequisite.** Require the ADR-0177 workload-identity
  token-exchange flow before any real port. Pros: the cleanest standards story; single issuer for all
  M2M. Cons: ADR-0177 is `proposed/planned`, not built — coupling MCP phase-2 to unbuilt infra
  strands real data behind a second epic. Rejected as a *blocker*; adopted as the *convergence
  target* for token minting under Option A, so MCP can ship on a simpler issuer now and inherit
  token-exchange later with zero resource-server change.

## Consequences

**Positive**
- Removes the #2206 blocker's root cause: the PDP is asked about the *real* agent and the *real*
  consent, so a real read port can replace the stub without exposing accounts.
- Revocation and expiry are honoured per call (live `/validate`), not frozen at token-issue time.
- Reuses shipped infrastructure (consent-service `/validate`, `AGENT_*` scopes, OIDC, the `agent:`
  sub convention) — small, well-scoped implementation surface.
- Keeps `propose_payment` firmly in the HITL + per-transaction-SCA regime (ADR-0126/0089).

**Negative**
- One consent-service round-trip per tool call (mitigated by the `frequencyPerDay` cache window and a
  short-lived in-process cache; PSD2 AISP cap is 4/day per consent).
- Pins a token-issuer contract (the `consent_id` claim + `AGENT_*` scopes) that must be documented
  and version-controlled, and re-verified when ADR-0177 changes the issuer.

**Neutral**
- Money-path change (`propose.payment` reachable with real identity): requires two approvals and a
  threat-model update (the existing `docs/threat-models/openbank-mcp-service.md`, PR #2200) on the
  implementing PR — not on this ADR.
- The `rest.rego` `attributes`-forwarding fix restamps every service's OPA bundle (fleet-wide), the
  same mechanics as #2142/#2152.

## Compliance impact

- PCI DSS: not applicable — no card PAN in scope; MCP reads are account/balance/transaction/consent.
- DORA:    not applicable — no change to ICT third-party or resilience posture beyond an added
  in-cluster dependency already covered by existing monitoring.
- GDPR:    data minimisation — account data is returned only within the live-validated
  `grantedAccounts`; PII stays masked (agents.yaml `defaults.pii: masked`); revocation propagates via
  the ADR-0126 sweeper/outbox, so consent withdrawal takes effect on the next call.
- PSD2:    account-access consent is enforced live per call via `consent-service /validate`
  (grantee-match, `isActive`, `hasScope`, `coversAccount`); AISP frequency cap (RTS Art. 10, as
  already implemented in ADR-0126, `frequencyPerDay`) bounds caching; `AGENT_INITIATE` /
  `propose_payment` stays SCA-gated and HITL — no PIS execution on the model's word.
- CNB:     not applicable — the CZ national profile consent scope group is covered by the same
  consent authority (ADR-0126); no additional CNB provision is introduced here.

## References

- ADR-0181 — MCP server exposing PSD2/admin read APIs to governed AI agents (the service this secures)
- ADR-0126 — Unified consent lifecycle (`/consents/{id}/validate`, `AGENT_QUERY`/`AGENT_INITIATE`, revoke/expire)
- ADR-0089 — Customer-facing AI assistant (the `AGENT_*` HITL + SCA regime `propose_payment` reuses)
- ADR-0034 — REST authorization PDP (the plane `tools/call` already queries)
- ADR-0177 — Workload-identity M2M auth via RFC 8693 (the token-minting convergence target)
- ADR-0031 D3b — pki-agent SVID / `AgentSvidVerifier` (Option B identity; defense-in-depth under A)
- Issue #2206 — the ordering blocker this ADR resolves; PR #2200 — threat model; PR #2230 — the CI guard
- PRs #2142 / #2152 — phase-2 OPA wiring + the `rest.rego` null-resource bridge fix
