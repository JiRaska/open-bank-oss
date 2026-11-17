---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authn, security, payments]
summary: "Customer MCP clients are MCP_CLIENT delegation grantees with read+propose only: allowlisted OAuth+PKCE, DPoP 5-min tokens, grant per party+client+key, throttled before OPA; MCP cannot write, the app authorises via SCA."
---

# ADR-0383 — Customer-authorised MCP channel: DPoP-bound delegation, layered throttling, propose-only writes confirmed by SCA in the app

## Context

**Adjacent decisions and why they do not cover this.**
- ADR-0090: TPPs reach the bank through the Berlin Group XS2A interface, so TPP access is out of
  scope here.
- ADR-0232: delegated access lives in `delegation-service`, not consent-service.
- ADR-0233 (proposed): bank-chartered agents can be delegation grantees that **execute within
  ceilings** and only propose above them. A third-party client is not a chartered agent.
- ADR-0193: an AP2 mandate is evidence into an SCA decision and may yield a
  delegated-authentication exemption.
- ADR-0374/0375: every outbound payment passes one admission chain whose first step is Authority.

None of these decides how a customer admits a third-party MCP client. Two of them, if applied
unchanged to that client, would let a model complete a payment without the customer
authenticating in the bank app. This ADR closes that gap for this channel.

ADR-0195 made `openbank-mcp-service` an OAuth 2.1 resource server for **governed agents**: the
token `sub` is an `agent:<id>` principal, its `consent_id` is live-validated at consent-service,
and the consent's grantee is that agent. ADR-0224 added a staff on-behalf-of channel. ADR-0089
gave customers a **first-party** assistant behind the customer edge. None of them decides how a
**customer** authorises a **third-party MCP client** (a desktop or hosted assistant the customer
chooses) to act on their data. This ADR decides that, and it is needed because a forensic read of
the current code shows that extending ADR-0195 unchanged to customers would be unsafe:

1. **Consent is bound to the client, not to the customer.** `ConsentService.validateConsent`
   checks `consent.granteeId == command.granteeId`, and `ConsentGate` passes the token's
   `agentId`. That is correct for one agent acting under its own charter. With a public client
   that thousands of customers install, the grantee is shared: any token carrying that client's
   `sub` and some customer's `consent_id` validates against that customer's consent. The tracked
   realms emit no `consent_id` mapper today, so the customer path does not exist yet. If someone
   built it naively, a client-chosen `consent_id` would be enough to read another customer's data.
2. **Customer data is read with a shared service principal.** The read ports call account,
   balance, transaction, statement and payment services through the M2M clients `openbank-services`
   and `openbank-mcp`. The only thing that limits *which* customer is read is `ConsentGate` inside
   MCP, so one defect in one read port exposes every customer.
3. **The per-account check lives in consent-service, and MCP's copy is only a mirror.**
   `ConsentGate` re-checks `accountIban in grantedAccounts` only when the list is non-null. That
   is correct: a null list on a valid answer means "all of the party's accounts", and
   consent-service has already checked the named account (`coversAccount`). For this channel the
   consequence is that account scoping depends entirely on a party-bound consent, which item 1
   shows is not party-bound.
4. **Throttling sits behind the expensive steps.** `McpRateLimiter.check` runs after caller
   resolution and after the OPA decision, so an unauthenticated or denied flood costs token
   validation plus a PDP call per request with no bound. It is keyed on `agentId` only. With a
   shared client, one customer exhausts the window for every other customer. The counters live
   in memory, valid only while `replicas: 1`, and reset on a pod roll. There is no concurrency cap
   and no global budget protecting the money-path read services.
5. **Instruction/data separation is advisory.** `McpUntrustedData` wraps results in markers and
   explains them in `initialize`. A third-party model is free to ignore both. A text control
   cannot be what stands between attacker-written payment references and a debit.
6. **MCP is not internet-reachable today.** It has no route, only an ingress NetworkPolicy, and
   it validates tokens from the staff `openbank` realm. Exposing it as-is would put customer
   traffic on the staff realm, against ADR-0065.

Write capability already has the right shape on paper: `propose_payment` is propose-only and
currently refuses (`UnwiredProposalPort`). ADR-0086 makes a single-use SCA challenge consume the
settlement gate for payments. This ADR makes "MCP proposes, the app authorises" a hard rule for
the customer channel.

## Decision

We will expose MCP to customers as a **delegation channel**. It opens only when every
prerequisite below has landed. Until then no route to mcp-service is created.

**Prerequisites (not built on main on 2026-11-17)**
- **P1:** an RFC 9449 DPoP proof verifier and a shared replay store, as the ADR-0320 primitive.
  ADR-0320 is `proposed/partial` and does not yet contain one.
- **P2:** the `MCP_CLIENT` grantee type in delegation-service (D3). `DelegationGrant` has no
  grantee type at all today.
- **P3:** the deployed Keycloak version is verified to issue DPoP-bound access and refresh tokens
  for a customers-realm public client. This is checked against a local Keycloak of that exact
  version, per the realm-template rule in CLAUDE.md. The same check covers the D4 exchange: an
  RFC 8693 exchange from a DPoP-bound subject token, with an `act` claim, a read-only scope and a
  ≤ 60 s lifetime. If either step fails, the channel does not open. There is no bearer-token
  fallback.
- **P4:** a Valkey connection for mcp-service: the secret, the egress NetworkPolicy, and
  fail-closed client configuration. It holds the DPoP `jti` store, the nonces and the D5 buckets.
  In-memory stores are acceptable only while no route exists, because a pod roll resets them and
  re-opens a replay window.
- **P5:** party-ownership enforcement in every downstream read service the channel touches
  (account, balance, transaction, statement, payment confirmation). Each service gets its own
  cross-party-deny IT before its tool is enabled on this channel.

**D1 — Identity, tenant separation and client admission**
- Tokens come only from the **customer realm** (ADR-0065). MCP validates them as a **separate
  OIDC tenant selected by `iss`**. `CallerContextResolver` applies these rules in order:
  1. A token whose `iss` is the customers realm is resolved **only** by the customer-channel
     path. Its `sub` is never inspected for an `agent:` prefix, and the OBO path is never tried.
  2. The `agent:` and OBO paths accept only a token whose `iss` is the staff realm.
  3. A customer token must carry `aud` = `openbank-mcp-service`, an `azp` in the allowlist, and
     `cnf.jkt`. Anything else is anonymous, which means denied.
- Only **allowlisted, bank-registered clients** are admitted:
  - dynamic client registration is off;
  - redirect URIs must match exactly, and desktop clients use RFC 8252 loopback redirects;
  - authorization code + PKCE (S256) only;
  - no implicit, password or device grants.
- The connect screen is rendered by the bank and requires SCA. It names:
  - the client;
  - the capabilities;
  - that data leaves the bank to the client's own provider;
  - the expiry.

**D2 — Sender-constrained, short-lived tokens; no long-lived bearer secrets**
- Access tokens are **DPoP-bound**, live **≤ 5 minutes**, and carry the audience
  `openbank-mcp-service` (RFC 8707). MCP checks the DPoP proof on every request:
  - `typ` is `dpop+jwt` and the alg is in the allow-list;
  - the proof header carries a public key only;
  - `htm`, `htu` and `ath` match the request and the token;
  - `iat` is within ±60 s and no older than 300 s;
  - `jti` has not been used before;
  - the proof key's thumbprint equals `cnf.jkt`.
- MCP issues a **server nonce** (RFC 9449 §8), so a proof cannot be pre-generated.
- The `jti` replay store is shared (Valkey) and **fails closed**. If the store is unavailable,
  every request is rejected.
- Long validity comes only from a **rotating, DPoP-bound refresh token**:
  - reuse of a rotated refresh token revokes the whole token family;
  - idle expiry is 30 days;
  - absolute expiry is the grant's expiry, at most 180 days, after which the customer
    reconnects with SCA.
- **Revocation takes effect on the next call, not at token expiry.** Every call re-reads the
  grant (D3). A revoked grant therefore denies even while its access token is still valid.
- No static API keys and no per-customer client certificates. A retail customer cannot operate a
  PKI, and DPoP gives the same proof of possession. mTLS-bound tokens (RFC 8705) are reserved for
  a later corporate channel under its own ADR. TPPs use the ADR-0090 XS2A interface.
- The app shows connected clients, allows revoking them, and notifies the customer when a new
  client is connected.

**D3 — The delegation grant is the single authority for a read**
- Connecting a client creates a `DelegationGrant` in delegation-service (ADR-0232) with a **new,
  distinct grantee type `MCP_CLIENT`**:
  - grantor is the customer's party;
  - grantee is the allowlisted client id;
  - the grant records the `jkt` from connection time.
- This is **not** the ADR-0233 `AGENT` grantee. ADR-0233 covers bank-chartered agents with
  execution ceilings and remains unchanged for them. `MCP_CLIENT` is its own type, so ADR-0233
  D1 and D2 need no amendment.
- An `MCP_CLIENT` grant carries **only** capabilities from an allow-list, which is read
  capabilities plus `account.propose-payment`. The aggregate refuses every other capability,
  whatever ceiling is set. That includes `account.initiate-payment`, any `*.manage*` capability
  and `delegation.manage`.
- MCP finds the grant server-side from `(party from sub, azp, cnf.jkt)` on **every** call, and
  checks the requested resource against it.
- **PSD2 consent and consent-service (ADR-0126) are not used on this channel.** `ConsentGate` and the
  ADR-0195 grantee check remain the path for governed agents only. This removes the shared-grantee
  defect in Context item 1 by construction: a grant names one grantor party, and the lookup key
  includes that party.
- Today the ADR-0375 gate `delegation-capability-enforcement` is **advisory**. The
  `MCP_CLIENT` allow-list is therefore enforced by the aggregate invariant and its negative unit
  test. That gate going enforced is a separate ADR-0375 follow-up.

**D4 — Downstream reads carry the customer, and MCP's credential cannot write**
- For each downstream read, MCP obtains a **customer-scoped token** by RFC 8693 exchange:
  - subject is the customer;
  - actor is MCP plus the client (`act` chain, ADR-0226);
  - audience is that one downstream service;
  - lifetime is at most 60 s.
- The exchanged token is in-cluster only and never leaves MCP, so it does not need to be
  sender-constrained. Its value is in the short lifetime and the per-service audience.
- Downstream services enforce party ownership themselves. A defect in an MCP read port can
  therefore no longer read another party. The shared `openbank-services` principal is not used on
  this channel.
- **The primary write control is authorization at two PDPs, not code shape.**
  1. **MCP's own PDP.** A customer-channel call is evaluated as principal type `HUMAN`, with id =
     the customer `sub`, `channel = mcp-customer` and `client = azp`. The policy allows this
     channel only the `query.*.readonly` capabilities and `propose.payment`
     (`McpToolRegistry` capability names), whatever the customer's roles.
  2. **Every downstream PDP.** The exchanged token carries only a read scope. Base `rest.rego`
     denies every non-read action to a token whose `act` chain names the MCP actor, identified by
     principal id per the OPA rule in CLAUDE.md, never by a `SERVICE` principal type. The single
     exception is the proposal-store create action that D6 introduces.
  - Both are proven with `opa eval` against the built bundles, with a must-deny and a must-allow
    control in every run.

**D5 — Layered throttling, cheapest first, failing closed**
1. **Edge (before any token work).** Per-IP request and new-connection limits plus a body-size cap
   on the live edge controller (today ingress-nginx, later Envoy Gateway per ADR-0324).
2. **MCP, before token validation.** A small shared bucket for requests that carry no token.
3. **MCP, after the local JWT and DPoP check, before the grant lookup and before OPA.** A
   cost-weighted token bucket per `(party, azp)` in Valkey.
   - This deliberately reverses the current "denied calls do not burn the window" ordering. A
     caller holding a valid token who floods denied calls must still be bounded, because each
     denied call costs a grant lookup and a PDP call.
   - Tool costs are data in a customer-channel cost table in mcp-service config. There is no
     shared charter: a shared fallback identity must not return, per `CallerContextResolver`.
4. Per-party daily quota, a **global** budget per downstream service, a concurrency cap per party,
   per-call timeouts, and mandatory pagination with bounded time windows and response size.
5. Circuit breaker / bulkhead to each downstream, with load shedding (`429` + `Retry-After`)
   rather than queueing.

**Every limiter fails closed.** A Valkey outage rejects calls; it never admits them unthrottled.
Throttled and denied calls are audited and metered like any other deny.

**D6 — Writes are propose-only; authorisation happens in the bank app, always**
- **MCP can never authorise an instruction.** No tool, capability or token on this channel can
  initiate, confirm or release a payment, change a limit, add a trusted beneficiary, or change
  security settings. MCP may only create a **proposal**.
- **What a proposal is and how it is stored**
  - It is an immutable record created through `paymentProposal.create`. Payee IBAN, amount,
    currency and reference are fixed at creation.
  - Approval re-reads those fields from the store, never from MCP or the model.
  - An identical open proposal (same payee, amount and currency) is deduplicated, not created
    twice.
- **How it reaches the customer**
  - It appears on a **new customer-facing approval surface**: customer-edge routes plus an app
    screen in the separate customer-app repository (ADR-0064). The surface reuses only the
    ADR-0227 `ApprovalItem` contract shape. The ADR-0227 inbox itself is a staff surface and is
    not used.
  - It is clearly labelled as coming from the named connected client, and never shown as if the
    customer had entered it.
  - Notifications for proposals are coalesced to at most one push per party per 15 minutes.
- **Approval**
  - A proposal executes only after the customer approves it in the bank app with full SCA and
    dynamic linking (amount + payee), consuming a single-use challenge (ADR-0086, ADR-0021). This
    is the same authorisation as an instruction entered in the app, with no weaker variant.
  - The approval screen shows only data the bank produced. For an external IBAN it shows the IBAN
    and the Verification of Payee outcome (ADR-0171). If there is no VoP result it says the payee
    is unverified. Model-supplied text is never shown as authoritative.
  - Each proposal is approved individually; bulk approval is not possible.
- **No SCA exemption** applies to a proposal from this channel:
  - not own-account;
  - not trusted beneficiary;
  - not an ADR-0193 delegated-authentication exemption from an AP2 mandate. A mandate presented
    here is recorded as evidence only.
- A proposal never adds the payee to trusted beneficiaries.
- **Limits:** at most 10 open proposals per party, at most 20 created per party per day, and
  proposals expire after 24 h.
- An approved proposal then passes the full ADR-0374 admission chain. Its Authority step
  (ADR-0375 D1) sees the customer as the actor, because the customer authorised it. The MCP client
  is never the debiting actor.

**D7 — Prompt-injection posture**
- The security boundary is D4 (no write grant), D6 (SCA in the app) and the OPA gate (ADR-0034),
  not the model. `McpUntrustedData` markers and PII masking stay as defence in depth.
- Free-text fields are length-capped and stripped of control characters and link/image markup
  (exfiltration via rendered URLs). Tools take only schema-validated, typed arguments. There is no
  URL, query-expression or free-form filter argument.
- Tool descriptions are static and versioned in the repository. `tools/list` is policy-filtered
  (ADR-0225).

## Alternatives considered

- **Extend ADR-0195 unchanged (client = consent grantee, bearer tokens).** Least work. Rejected:
  a shared public client becomes a cross-customer read path (Context items 1–2).
- **Reuse ADR-0233 `AGENT` grantees.** One grantee concept fewer. Rejected: ADR-0233 requires a
  chartered agent identity and execution ceilings. A third-party client is neither, and bending
  ADR-0233 would silently widen it.
- **Allow execution within ceilings for MCP clients (ADR-0233 D2 shape).** Gives the client
  autonomy for small payments. Rejected: the ceiling bounds the loss, not the likelihood. An
  injected model drains up to the ceiling on every grant, and the customer never sees the payment.
- **Static per-customer API keys.** Simple for clients. Rejected: long-lived bearer secrets with no
  binding and no rotation story.
- **Per-customer mTLS client certificates.** Strong binding. Rejected for retail: customers cannot
  operate a PKI, and DPoP gives the same proof of possession. Kept for a future corporate channel.
- **Let MCP authorise low-value payments directly (with an exemption).** Better UX. Rejected: a
  third-party model reads attacker-written data, so any write the model can complete alone is an
  injection-to-debit path. The decision rule is that every MCP-originated instruction carries the
  same in-app authorisation as one entered in the app.

## Consequences

**Positive**
- A stolen access token is useless without the DPoP key and expires in minutes.
- A grant cannot be used across customers, clients or keys, and revocation applies on the next
  call.
- Neither MCP's own principal nor the exchanged customer token can perform a write other than
  creating a proposal. An injected model can at worst create a proposal, and the customer then
  rejects it in the app.
- Floods are shed before token validation or OPA calls, and money-path reads have a global
  ceiling.

**Negative**
- New work across mcp-service, delegation-service, libs, OPA data, the customers realm and the
  customer edge.
- The approval-inbox screen lives in the separate customer-app repository (ADR-0064).
- Clients without DPoP cannot connect. Customers reconnect at least every 180 days.
- A DPoP key and refresh token stolen together from a compromised customer device still allow
  read access until revocation. This is mitigated by notifications, the connected-clients view
  and anomaly alerts, not eliminated.

**Neutral**
- The governed-agent (ADR-0195), staff OBO (ADR-0224) and chartered-agent allowance (ADR-0233)
  paths are unchanged.

**Enforcement.**
- The primary write control is D4: OPA data grants this channel no write action, proven by
  `opa eval`.
- A tripwire gate `mcp-customer-channel-propose-only` (planned, enforced once it lands) fails on
  any non-GET REST client in mcp-service other than the proposal store. It is a backstop, not
  proof.
- The `MCP_CLIENT` capability allow-list is an aggregate invariant with a negative unit test.
- The existing `mcp-real-port-requires-caller-auth-first` gate stays.
- `docs/threat-models/openbank-mcp-service.md` gains the customer channel before any route exists.

### Delivery check

- **No write grant at MCP.** `opa eval` on the built mcp bundle, for a `channel = mcp-customer`
  principal, answers `allow=false` on any capability outside `query.*.readonly` and
  `propose.payment`, and `allow=true` on `query.balance.readonly`.
- **No write grant downstream.** `opa eval` on the sepa-payment and domestic-payment bundles, for
  a token whose `act` chain names the MCP actor, answers `allow=false` on `sepaPayment.create` and
  `domestic-payment.create`, and `allow=true` on `sepaPayment.read`.
- **Allow-list invariant.** The delegation-service unit test "MCP_CLIENT grant with
  account.initiate-payment is rejected" exists and passes, and fails when the invariant is
  removed.
- **DPoP binding.** An IT over real HTTP shows:
  - no DPoP proof gives 401;
  - a proof for another key gives 401;
  - a replayed `jti` gives 401.
- **Cross-customer isolation.** Per P5, an IT in each enabled downstream service shows customer A's
  exchanged token against customer B's resource is denied, and an IT at MCP shows the same.
- **Throttle ordering.** An IT shows a flood of tokenless requests is throttled with zero OPA calls
  and zero grant lookups recorded.
- **Proposal approval.** An IT shows a proposal executes only after a consumed SCA challenge, and a
  second approval of the same proposal is refused.
- **Exposure last.** `kubectl get httproute,ingress -A | grep mcp` lists nothing until all of the
  above hold.

## Compliance impact

- PCI DSS: not applicable — the channel exposes no cardholder data and no tool returns PAN.
- DORA:    ICT third-party risk engagement — customer-chosen MCP clients are external, so allowlisting, revocation and audit of each client are in scope.
- GDPR:    lawful basis and transparency engagement — the consent screen discloses that data leaves the bank to the client's provider; revocation stops further transfer.
- PSD2:    SCA engagement — every MCP-originated payment requires full SCA with dynamic linking in the bank app and takes no exemption; AIS-style read access is re-consented at least every 180 days.
- CNB:     not applicable — no reporting or supervisory artefact changes.

## References

- ADR-0021, ADR-0034, ADR-0064, ADR-0065, ADR-0086, ADR-0089, ADR-0090, ADR-0126, ADR-0132, ADR-0193,
  ADR-0195, ADR-0224, ADR-0171, ADR-0225, ADR-0226, ADR-0227, ADR-0232, ADR-0233, ADR-0320, ADR-0324, ADR-0374,
  ADR-0375
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/mcp/CallerContextResolver.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/read/ConsentGate.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/ratelimit/McpRateLimiter.kt`
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/application/usecase/ConsentService.kt`
- RFC 9449 (DPoP), RFC 8693 (token exchange), RFC 8707 (resource indicators), RFC 8705 (mTLS), RFC 8252 (native apps)
