---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authn, security, payments]
summary: "Customers reach MCP only via allowlisted OAuth 2.1+PKCE clients on the customer realm with DPoP-bound 5-min tokens and consent bound to party+client; throttled pre-auth and per party; MCP only proposes, the app authorises via SCA."
---

# ADR-0383 — Customer-authorised MCP channel: DPoP-bound delegation, layered throttling, propose-only writes confirmed by SCA in the app

## Context

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
3. **`ConsentGate` fails open on a null grant list.** It applies `accountIban !in granted` only
   when `granted != null`. consent-service rechecks `coversAccount` itself, so this is not
   exploitable today, but the MCP-side check is decorative.
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

We will expose MCP to customers as a **delegation channel** with these properties.

**D1 — Identity and client admission**
- Tokens come only from the **customer realm** (ADR-0065), through the customer edge. MCP
  validates them as a **separate OIDC tenant** selected by issuer, and a customer-realm token can
  never take the `agent:` or OBO paths in `CallerContextResolver`. Tenant is chosen by `iss`,
  never by a `sub` prefix.
- Only **allowlisted, bank-registered clients** are accepted:
  - open dynamic client registration is off;
  - redirect URIs must match exactly, and desktop clients use loopback redirects;
  - authorization code + PKCE (S256) only;
  - no implicit, password or device grants.
- The consent screen is rendered by the bank. It names the client, the scopes, that the data
  leaves the bank to the client's provider, and the expiry. The screen requires SCA.

**D2 — Sender-constrained, short-lived tokens; no long-lived bearer secrets**
- Access tokens are **DPoP-bound** (RFC 9449, ADR-0320 primitive), live **≤ 5 minutes**, and have
  the audience `openbank-mcp-service` (RFC 8707). A token without a valid DPoP proof for its `jkt`
  is rejected. DPoP `jti` replay is checked inside the proof lifetime.
- Long validity comes only from a **rotating, DPoP-bound refresh token**:
  - reuse of a rotated refresh token revokes the whole token family;
  - idle expiry is 30 days;
  - absolute expiry is the consent expiry, at most 180 days, after which re-consent with SCA is
    required.
- No static API keys and no per-customer client certificates. A certificate gives a retail
  customer the same binding as DPoP at the cost of a PKI they cannot operate. mTLS-bound tokens
  (RFC 8705) are reserved for a later corporate channel under its own ADR.
- The customer sees connected clients in the app, can revoke them, and is notified when a new
  client is connected.

**D3 — Consent bound to customer AND client AND key**
- The delegation consent (consent-service, ADR-0126) is validated against a triple:
  - the customer `sub`/`party_id` from the token;
  - the client `azp`;
  - the token's `cnf.jkt` recorded at grant time.
- `consent_id` is looked up server-side from that triple. A client never supplies it, and a
  claim the client can influence is never trusted.
- `ConsentGate` fails closed when the grant list is absent.

**D4 — Downstream reads carry the customer, not a shared operator**
- MCP obtains a **customer-scoped downstream token** by RFC 8693 exchange:
  - subject is the customer;
  - actor is MCP plus the client (`act` chain, ADR-0226);
  - audience is per downstream service.
- Downstream services enforce party ownership themselves, so a defect in an MCP read port can no
  longer read another party.
- The shared `openbank-services` principal is not used on this channel.

**D5 — Layered throttling, cheapest first**
1. Gateway/WAF: per-IP and new-connection limits, plus a body-size cap, **before** any token
   validation.
2. Per `(party, client)` cost-weighted token bucket in Valkey (ADR-0320 primitive, ADR-0132 key
   shape), applied **before** the OPA call. Tool costs are data in the charter.
3. Per-party daily quota and a **global** budget per downstream service.
4. A concurrency cap per session and per party, per-call timeouts, and mandatory pagination with
   bounded time windows and response size.
5. Circuit breaker / bulkhead to each downstream, with load shedding (`429` + `Retry-After`)
   rather than queueing.

Throttled and denied calls are audited and metered like any other deny.

**D6 — Writes are propose-only; authorisation happens in the bank app, always**
- **MCP can never authorise an instruction.** No tool, scope or consent on this channel can
  initiate, confirm or release a payment, change a limit, add a trusted beneficiary, or change
  security settings. MCP may only create a **proposal**.
- A proposal executes only after the customer approves it in the **bank app** with full SCA and
  dynamic linking (amount + payee). The approval consumes a single-use challenge (ADR-0086,
  ADR-0021). This is the same authorisation as for an instruction entered in the app, with no
  weaker variant.
- The approval screen shows **bank-rendered** fields only: payee name from the bank's own
  resolution, IBAN and amount. Model-supplied free text is never shown as if it were authoritative.
- **No SCA exemption** applies to a proposal that originates on this channel, including own-account
  and trusted-beneficiary exemptions. A proposal never adds the payee to trusted beneficiaries.
- Proposals expire, are rate-limited per party, and cannot be approved in bulk, so an injected
  model cannot cause approval fatigue.

**D7 — Prompt-injection posture**
- The security boundary is D6 and the OPA gate (ADR-0034), not the model. `McpUntrustedData`
  markers and PII masking stay as defence in depth.
- Free-text fields are length-capped and stripped of control characters and link/image markup
  (exfiltration via rendered URLs). Tools take only schema-validated, typed arguments. There is
  no URL, query-expression or free-form filter argument.
- Tool descriptions are static and versioned in the repository. `tools/list` is policy-filtered
  (ADR-0225).

## Alternatives considered

- **Extend ADR-0195 unchanged (client = grantee, bearer tokens).** Least work. Rejected: Context
  items 1–2 show that it makes a shared client a cross-customer read path.
- **Static per-customer API keys.** Simple for clients. Rejected: long-lived bearer secrets,
  copied into config files, with no binding and no rotation story.
- **Per-customer mTLS client certificates.** Strong binding. Rejected for retail: customers cannot
  operate a PKI, and DPoP gives the same proof-of-possession property. Kept for a future corporate
  channel.
- **Let MCP authorise low-value payments directly (with an exemption).** Better UX. Rejected: a
  third-party model reads attacker-written data, so any write the model can complete alone is an
  injection-to-debit path. The owner requires the same in-app authorisation as for app-entered
  instructions.

## Consequences

**Positive**
- A stolen access token is useless without the DPoP key and expires in minutes. Consent is
  unusable across customers or clients.
- An injected model can at worst create a proposal, and the customer then rejects it in the app.
- Floods are shed before token validation or OPA calls, and money-path reads have a global ceiling.

**Negative**
- New work: a customer-realm OIDC tenant in MCP, DPoP verification, a consent binding triple,
  token exchange plus downstream party enforcement, a Valkey limiter, a proposal store with an
  in-app approval UI.
- Clients that do not support DPoP cannot connect. Customers must re-consent at least every 180
  days.
- A DPoP key and refresh token stolen together from a compromised customer device still allow
  read access until revocation. This is mitigated by notifications, the connected-clients view
  and anomaly alerts, not eliminated.

**Neutral**
- The governed-agent (ADR-0195) and staff OBO (ADR-0224) channels are unchanged.

**Enforcement.**
- A new checker under `.github/scripts/` (gate `mcp-customer-channel-propose-only`, enforced): no
  REST client in `openbank-mcp-service` may target a non-GET path of a payment, limit,
  beneficiary or SCA service except the proposal store.
- The existing `mcp-real-port-requires-caller-auth-first` gate stays.
- `docs/threat-models/openbank-mcp-service.md` is extended with the customer channel before any
  route is exposed.

### Delivery check

- `git grep -n 'granted != null' openbank-mcp-service/src/main` prints nothing.
- An MCP request without a DPoP proof, or with a proof for another key, answers 401, asserted by
  an IT driving real HTTP.
- An IT with customer A's token against a consent of customer B answers deny.
- `grep -n 'mcp-customer-channel-propose-only' -A3 .github/gates/gates.yaml` shows
  `mode: enforced`.
- An IT shows a flood of unauthenticated requests is throttled with zero OPA calls recorded.
- No HTTPRoute exposes mcp-service until all of the above hold.

## Compliance impact

- PCI DSS: not applicable — the channel exposes no cardholder data and no tool returns PAN.
- DORA:    ICT third-party risk engagement — customer-chosen MCP clients are external, so allowlisting, revocation and audit of each client are in scope.
- GDPR:    lawful basis and transparency engagement — the consent screen discloses that data leaves the bank to the client's provider; revocation stops further transfer.
- PSD2:    SCA engagement — every MCP-originated payment requires full SCA with dynamic linking in the bank app and takes no exemption; AIS-style read access is re-consented at least every 180 days.
- CNB:     not applicable — no reporting or supervisory artefact changes.

## References

- ADR-0021, ADR-0034, ADR-0065, ADR-0086, ADR-0089, ADR-0126, ADR-0132, ADR-0195, ADR-0224,
  ADR-0225, ADR-0226, ADR-0320
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/mcp/CallerContextResolver.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/read/ConsentGate.kt`
- `openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/ratelimit/McpRateLimiter.kt`
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/application/usecase/ConsentService.kt`
- RFC 9449 (DPoP), RFC 8693 (token exchange), RFC 8707 (resource indicators), RFC 8705 (mTLS), RFC 8252 (native apps)
