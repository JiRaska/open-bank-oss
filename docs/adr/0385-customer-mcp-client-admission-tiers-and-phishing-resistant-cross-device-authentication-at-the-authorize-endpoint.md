---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [ai-agents, authn, security]
summary: "Amends ADR-0383: MCP clients are admitted by allowlist tier — hosted ones only as authenticated confidential clients, local ones only with DPoP; authorize = fresh passkey in the starting browser plus a context-linked SCA in the app."
---

# ADR-0385 — Customer MCP client admission tiers and phishing-resistant cross-device authentication at the authorize endpoint

## Context

**This ADR amends ADR-0383 (`proposed/planned` on main). It does not supersede it.**
`supersedes` stays empty and every amended section is listed in D6. This follows the precedent
of ADR-0260, which amends ADR-0006 in its text. SCHEMA.md has no field for a partial amendment.

ADR-0383 needs amending for two reasons.

1. **ADR-0383 D2/P3 set "no bearer fallback" for every client.** That admits no hosted client
   at all:
   - Per the MCP authorization specification (2026-07-28) and the published connector
     documentation of hosted assistant platforms, no major MCP host is verified to support DPoP
     in its OAuth client.
   - Hosted assistant platforms run the OAuth client server-side, with fixed HTTPS callbacks.
     Some offer confidential-client authentication: a key-signed client assertion against a
     published JWKS, or a platform mTLS certificate. Others behave as public clients.
   - Local MCP clients are public clients with loopback or custom-scheme redirects. The MCP
     specification notes that client metadata documents cannot stop localhost impersonation.
   - The replay bound DPoP gives can, for a hosted client, also come from binding the refresh
     token to an authenticated client credential. For a local public client nothing else binds
     the token.
2. **ADR-0383 D1 does not say how the customer authenticates in the browser where the assistant
   opened the authorize URL.** That is usually a computer while the credential is on the phone,
   which makes it a cross-device flow. RFC 10027 (BCP 247, Aug 2026) treats such flows as
   phishing-prone. It asks for:
   - a risk assessment;
   - proximity where possible;
   - short single-use codes;
   - context display and request binding;
   - rate limits against consent flooding;
   - sender-constrained tokens;
   - authenticate-then-initiate.

   It allows the device grant only as a last resort and not for high-value resources. It
   recommends WebAuthn hybrid with proximity.

**Facts verified on origin/main (2026-11-17) that shape the decision.**
- **A bare `CONSENT_GRANT` challenge is not context-linked.** In
  `openbank-sca-service/.../domain/model/DeviceApproval.kt`, `dynamicLinkingPayload` signs
  `id|decision` plus the payment fields, and those are empty for a consent. Only `APPROVAL`
  signs `approvalRequestId|payloadSha256`, and `ScaService.requireApprovalLinking` refuses an
  `APPROVAL` initiate without both.
- **Concurrent consent challenges collapse.** `buildIdempotencyKey` is party + purpose + method
  + payment fields + redirectUrl, with a TTL of 300 s. Two concurrent consent flows for one party
  therefore collapse into one challenge.
- **consent-service accepts any `CONSENT_GRANT` of the party.** `ConsentService.kt:147` checks
  only `purpose == "CONSENT_GRANT"` and the party.
- **The deployed passkey flow can skip the passkey.** The customers realm
  (`customers-realm-template.json`) flow `openbank-passkey-browser` is `auth-cookie` ALTERNATIVE
  then forms, so an existing SSO cookie skips the passkey.
- **The realm passkey policy may suppress hybrid.** `webAuthnPolicyPasswordlessAuthenticatorAttachment`
  is `platform`, which may hide the hybrid (phone) option. Attestation is `none`.
- **Other realm settings.** `registrationAllowed` is `true`. `ssoSessionIdleTimeout` is 1800 s.
  `revokeRefreshToken` is `true` and `refreshTokenMaxReuse` is `0`.
- **Keycloak version.** The deployed image is Keycloak 26.8.1 (`keycloak.yaml`).
- **The bank's own app client.** `openbank-app` allows the redirect `http://localhost:*`. This
  is filed as #16612.
- **The shared app-link path.** The `/pay/v1*` app-link path is claimed by two app ids
  (`apple-app-site-association`, ADR-0095 pilot).
- **The planned shared rate limiter fails open.** ADR-0320 P4's planned limiter is fail-open
  when Valkey is down.
- **ADR-0320 P2 is not built.** It is the DPoP check and the mTLS `cnf.x5t#S256` check (#10928).
- **ADR-0384 is not on main.** It is open as #16607; its D4 is the per-client kill switch.

**Adjacent decisions and why they do not cover this.**

| ADR | What it decides | Why it does not cover this |
|---|---|---|
| ADR-0383 | the channel | amended here |
| ADR-0065 | the customers realm for the bank's own app | not third-party clients |
| ADR-0066 | passkey-first login | decides the factor, not its cross-device use |
| ADR-0021 / ADR-0086 | decoupled approval and single-use consume | reused here |
| ADR-0335 | the reserved-namespace SCA pattern | reused here |
| ADR-0095 | BLE capability | cited for capability only |
| ADR-0320 | egress (P1) and sender-constraint (P2) | prerequisites |
| ADR-0384 | observability, signals and suspend | prerequisite |
| ADR-0090 | XS2A for TPPs | out of scope |

**Deadline:** none regulatory. ADR-0383's order binds: no route to mcp-service until every
prerequisite holds.

## Decision

We will admit MCP clients by an allowlist-defined tier. Every customer will authenticate at the
authorize endpoint with a fresh passkey assertion in the browser that started the flow, and will
confirm a context-linked SCA challenge in the bank app before a code is issued.

### D1 — Client admission

**Common rules**
- **Preferred path: an official listing.** A client is preferably connected through the bank's
  official listing or plugin entry on the hosted platform, so the customer types no URL.
- **The resource URL is published and shown.** The official MCP resource URL is published in the
  app and on the bank website. The exact resource URL is shown on the consent screen and in the
  app.
- **Only our resource.** The authorization server rejects any RFC 8707 `resource` other than the
  mcp-service resource, and access tokens carry `aud` = `openbank-mcp-service`.
- **Unauthenticated callers get nothing.** An unauthenticated MCP call gets 401 with RFC 9728
  metadata, no tools and no data. A token with any other `aud`, including an `openbank-app` token,
  is rejected.
- **The tier comes from server-side config, never from the token.** Tier is a field of the
  single MCP client allowlist declaration, keyed by `azp`. It is never read from a token claim
  or from client-supplied metadata. It is assigned by the client's registration behaviour
  (redirect type, whether it authenticates, whether it sends DPoP), never by product name.
- **One declaration drives both mcp-service and Keycloak.**
  - The same declaration drives mcp-service's admission and the realm client config.
  - A realm assertion fails the gate on any mismatch, and mcp-service refuses to boot on one.
  - For Tier H the assertion requires `publicClient=false` with a client authenticator.
  - For Tier L it requires DPoP-bound tokens (`dpop.bound.access.tokens=true`).
- **No public hosted clients.** A hosted client that behaves as a public client (no client
  authentication) is not admitted, whatever its popularity.
- **Every MCP client in the realm is asserted to have:**
  - authorization code + PKCE S256 only;
  - no direct-access grant, no implicit grant, no device grant;
  - no service account;
  - no dynamic client registration;
  - exact redirect URIs;
  - refresh rotation with `refreshTokenMaxReuse` 0. It is never raised. Reuse revokes the family
    for public and confidential clients alike.
- **Loopback redirects.** A loopback redirect is `http://127.0.0.1:{port}` or `http://[::1]:{port}`
  with a variable port (RFC 8252). `localhost` is not accepted.
- **Discovery.** Discovery publishes RFC 9728 protected-resource metadata. The authorization
  response carries RFC 9207 `iss`.
- **Token lifetimes.** Access tokens live at most 5 minutes. Scope is read + propose only
  (ADR-0383 D3/D4).
- **Refresh lifetime.** ADR-0383's 30-day idle refresh lifetime exceeds
  `ssoSessionIdleTimeout` (1800 s). It is therefore delivered as an `offline_access` token under
  `offlineSessionIdleTimeout` (30 days), and P3 tests that.

**Tier H — hosted confidential client**
- Admitted only with a fixed HTTPS redirect on an allowlisted host, and with client
  authentication at **both** the token and refresh endpoints.
- **Default: `private_key_jwt`** against an allowlisted JWKS URL whose key-set hash is pinned.
- **mTLS (RFC 8705)** where the platform offers it. The token then carries `cnf.x5t#S256`. This
  depends on ADR-0320 P2 (unbuilt) and on the edge forwarding a verified client certificate.
  Until both exist, mTLS is not offered.
- **Client secret: exception only.** A client secret is allowed only as a recorded, time-boxed
  exception (≤ 6 months, owner risk acceptance) when the platform offers nothing else. The secret
  is ≥ 256 bits, per bank, rotated ≤ 90 days and never logged.
  - The exception's expiry date is a field of the allowlist declaration.
  - After that date the authorization server refuses the client's secret authentication.
- **Bearer access tokens** are allowed only under these conditions. This is the sole exception to
  ADR-0383's "no bearer fallback".
- **DPoP when supported.** If a Tier H client supports DPoP it is used. Once a grant has recorded
  a `jkt`, a bearer token for that grant is refused.
- **Grant key.** Tier H grants without a `jkt` are keyed `(party, azp)`.
- **Rotation keeps grants.** Rotating the client key or secret does not invalidate existing
  grants. A rotation test proves it.

**Tier L — local public client**
- DPoP is required. A Tier L `azp` presenting a token without `cnf.jkt`, or without a valid
  proof, gets 401.
- A local client without DPoP is not admitted.
- `dpop_jkt` is sent at PAR where the authorization server supports it (RFC 9449 §10). Without
  that, the binding starts at the token endpoint.
- No token-endpoint DPoP nonce is required by this ADR. ADR-0383's resource-server nonce stands.

**Client ID Metadata Documents (CIMD)**
- **Pre-registration is the default.** CIMD is used only if P3 shows that Keycloak 26.8.1
  supports it. Otherwise clients are pre-registered only.
- **Who fetches.** The authorization server fetches CIMD, only for allowlisted metadata URLs.
- **Fetch conditions, all required:**
  - HTTPS;
  - no redirects;
  - resolved-IP pinning with private, loopback and link-local ranges blocked;
  - size and time caps;
  - through the ADR-0320 P1 egress allowlist.
- **Pinned keys.** The `jwks` key-set hash is pinned.
- **The bank names the client.** The display name and logo shown on consent are assigned by the
  bank, never taken from vendor metadata.
- **A changed document disables the client.** A changed document hash disables the client until
  a four-eyes acceptance.

### D2 — Authentication at the authorize endpoint

1. **PAR is required (RFC 9126).**
   - `request_uri` is single-use and lives ≤ 60 s.
   - Parameters sent beside the `request_uri` that differ from the stored request are rejected.
   - A `request_uri` presented by another `client_id` is rejected.
   - PKCE that is absent or `plain` is rejected.
2. **A fresh passkey in the browser that started the flow.**
   - Every MCP client is bound per client to a dedicated authentication flow override with
     **no `auth-cookie` alternative**. That binding is the control.
   - `max_age` is not relied on: a client may omit it or misstate it, and the binding still
     requires a fresh assertion.
   - With a valid SSO cookie:
     - `prompt=none` yields `login_required` and never a code;
     - `prompt=create` (registration) is refused;
     - `kc_action` is ignored or refused;
     - `login_hint` and `kc_idp_hint` cannot skip the assertion;
     - no refresh or silent-renew path issues a code without a fresh assertion.
   - If the passkey is on the phone, the browser and OS offer WebAuthn hybrid. The customer scans
     the OS-provided QR with the phone camera, and the protocol requires proximity.
   - Authenticator attachment is relaxed for this flow if `platform` hides hybrid. P3 verifies
     that hybrid is offered in real browsers under the realm policy.
   - This is authenticate-then-initiate. There is no bank-made QR, and no password, SMS, e-mail
     link or device-code fallback.
3. **Registration and recovery are unreachable** from an MCP authorize. A passkey, or an SCA
   device, enrolled less than 72 h ago cannot authorize an MCP connect. Account recovery is
   explicitly out of this path.
4. **Same device** (the assistant runs on the phone):
   - The fresh passkey assertion still runs in the phone's browser.
   - The flow then switches app-to-app through an opaque single-use handle bound to the
     browser's authentication session.
   - The SCA challenge's `partyId`, and `expectedPartyId` at consume, must equal the party that
     asserted the passkey in that session.
   - A handle presented by another party's app is refused.
   - The app approval completes only that session, and the code is returned only to that session.
     A handle presented from another browser session is refused.
   - The link path is new and claimed only by the `tech.openbank.app` app id. It is not matched
     by `/pay/v1*` and is not claimed by any other app id.
   - The web fallback page only tells the customer to install the app.

### D3 — Consent: a context-linked SCA challenge in the app

- **A new SCA purpose, `MCP_CLIENT_CONNECT`,** in ADR-0335 reserved-namespace style.
- **Initiate refuses it without linking**, the same way `requireApprovalLinking` refuses
  `APPROVAL`. It must carry:
  - `approvalRequestId`, the PAR request id;
  - `payloadSha256` over a canonical context: `client_id`, redirect host, resource URL, scopes,
    hash of the `request_uri`, and expiry.
- **The device signs** `challengeId|decision|MCP_CLIENT_CONNECT|approvalRequestId|payloadSha256`.
- **The idempotency key includes `payloadSha256`.** Two concurrent flows therefore get two
  challenges.
- **The app renders only from the signed context.** It shows:
  - the bank-assigned client name;
  - the redirect host;
  - the exact resource URL;
  - capabilities: "reads your data and can propose payments; it can never pay by itself";
  - that data goes to the platform's own provider;
  - the expiry;
  - the warning "approve only if you just pressed Connect in your own assistant".
- **The browser shows the same context.**
- **A code only after consume.** The authorization code (single-use, ≤ 60 s) is issued only after
  the challenge is consumed for **that** `approvalRequestId`.
- **consent-service refuses `MCP_CLIENT_CONNECT`** as evidence for a PSD2 consent.
- **The purpose is a reserved namespace.** It is registered in sca-service `ReservedNamespace`
  (ADR-0335), so `ConsumerScope.General` cannot spend it. That matters because `consume()` does
  not compare the purpose. Only the authorization server's scoped consumer can spend it.
- **A push text is required.** `MCP_CLIENT_CONNECT` gets a `STATIC_PUSH_MESSAGES` entry, because
  without one `buildPushMessage` fails initiate at its `checkNotNull`.
- **The withheld-code authenticator** in Keycloak follows this spec:
  1. The `challengeId` lives only in a Keycloak auth-session note and is never taken from the
     browser.
  2. At consume it recomputes `approvalRequestId` and `payloadSha256` from the stored PAR request.
  3. It consumes with `expectedPartyId` equal to the session's party.
  4. The consume is atomic (CAS) and comes before code issuance. A failure after the consume
     fails closed: no code, and a fresh connect is required.
  5. A forked or multi-tab session issues at most one code.
  6. The auth-session lifespan is ≤ the 300 s challenge TTL.
  7. The poll endpoint is rate-limited.
- **No location or device-mismatch warning.** It is not a control here; it is the wrong signal.
  The defences are proximity, the 60 s `request_uri` and facts from the vendor session.

### D4 — Throttling, abuse and platform compromise

- **ADR-0384 D4 and its signals are a prerequisite**, added to ADR-0383 as P6.
- **Fail-closed limiters only.** Throttling uses per-`(party, azp)` buckets, with a per-client
  aggregate budget as their ceiling, in fail-closed mode. **The ADR-0320 P4 fail-open limiter is
  not reused for this channel.**
- **Shared vendor egress.** Many customers share one vendor egress IP.
  - Allowlisted vendor egress ranges get a separate, higher edge limit, not an exemption, because
    the ranges are shared and may change.
  - Per-`(party, azp)` buckets bound each customer.
  - The per-party cap is set well below the per-client ceiling: at most 1/20 of it by default.
- **Consent-flood caps.** At most 3 pending authorizations and 5 new connections per party per
  day. A breach is refused and audited.
- **Breadth signals** to ADR-0384:
  - distinct parties read per minute per `azp`;
  - refreshes without tool calls;
  - a new connection followed by bulk reads.
- **New-connection notice.** A new connection triggers a push and a 24 h in-app banner with
  one-tap revoke.
- **Emergency suspend of a client.** It revokes the client's tokens in Keycloak and disables its
  credential, as well as denying it at mcp-service.
- **Data minimisation defaults:**
  - transactions ≤ 90 days;
  - page size ≤ 50;
  - statements ≤ 12 months.

**RFC 10027 §6.1 mapping**

| Category | Mitigation |
|---|---|
| Proximity | WebAuthn hybrid (D2.2); same-device handle (D2.4) |
| Short-lived / single-use | `request_uri` and code ≤ 60 s; single-use SCA consume (D2.1, D3) |
| Request binding / context | PAR-held request; `payloadSha256` over the context, signed by the device (D3) |
| Authenticate-then-initiate | fresh passkey before consent (D2.2) |
| Rate limits vs flooding | pending and new-connection caps (D4) |
| Sender constraint | DPoP for Tier L; credential-bound refresh for Tier H (D1) |
| Device grant | never (D1) |
| User notification | warning, push, banner, revoke (D3, D4) |

### D5 — Accepted residual risk

All of this is bounded by read + propose only (ADR-0383 D4/D6).
- **Attacker-initiated consent.** A victim can complete a connect the attacker started. Neither
  the passkey nor SCA stops this. D3 and D4 detect it and limit it.
- **Proximity is unverifiable by the bank.** Proximity is enforced by the client and the
  authenticator, and the relying party cannot verify it (attestation is `none`).
- **Synced passkeys.** A synced passkey is only as strong as its cloud account. The device-bound,
  non-syncable SCA key in the app (ADR-0066) is the second factor that compensates.
- **Malware** on either device.
- **A compromised hosted platform** can read within the per-client budget until it is suspended.
- **Prompt-injected exfiltration** of what the D4 defaults allow.

### D6 — Sections of ADR-0383 amended

- **D1:** item 3 now requires `cnf.jkt` only for Tier L. Client admission is by allowlist tier
  (D1). The connect screen is D2/D3.
- **D2:** the DPoP requirement and "no bearer fallback" now apply to Tier L. Tier H follows D1.
- **D3:** the grant key is `(party, azp, jkt)`, or `(party, azp)` for a Tier H grant without
  DPoP.
- **D5:** adds per-client ceilings, the vendor-egress exemption and fail-closed only (D4).
- **P3:** extended (D7). P6 is added (ADR-0384).
- **Delivery checks:** extended (below).
- **Consequences:** "Clients without DPoP cannot connect" now applies to Tier L only.

### D7 — Amended P3: verified on a local Keycloak 26.8.1

1. DPoP-bound tokens for a Tier L client, and `dpop_jkt` at PAR.
2. Client authentication enforced at refresh for Tier H, with `private_key_jwt` and a secret.
3. PAR required, with the D2.1 negatives.
4. RFC 8707 `resource` restriction, and RFC 9728 and RFC 9207.
5. A fresh-WebAuthn flow with no cookie alternative, and hybrid offered in real browsers.
6. The issued code withheld until an external SCA consume.
7. Offline-token refresh lifetime.
8. The per-client flow binding, and the withheld-code authenticator (D3).
9. Whether CIMD is supported.
10. The ADR-0383 D4 exchange.

Any failure keeps the channel closed.

## Alternatives considered

- **Bank-rendered QR plus a matching code without proximity, as the authentication step.**
  Rejected: QRLJacking relays the live QR **and** the code to the attacker's page, so number
  matching does not stop it (RFC 10027: bound data can be phished).
- **Bare `CONSENT_GRANT` for the app approval.** Less work. Rejected: it signs no context, it
  collapses concurrent flows, and consent-service would accept it for PSD2 consent.
- **DPoP for every client (ADR-0383 unchanged).** Rejected: it excludes every hosted platform. A
  credential-bound refresh token reaches the same replay bound.
- **Public hosted clients, dynamic client registration, the device grant, a local client without
  DPoP.** Rejected: an unbound leaked refresh token; the end of the allowlist; the canonical
  consent-phishing grant; localhost impersonation.
- **Client secret as the default for Tier H.** Rejected: a shared secret. It is an exception only.
- **Location or device mismatch as a control.** Rejected: an unreliable signal, which teaches
  customers to ignore it.
- **Password, SMS or e-mail-link fallback.** Rejected: phishable, and ADR-0066 creates no
  password.

## Consequences

**Positive**
- Hosted assistants can connect.
- The SCA approval is bound to one authorization request.
- An SSO cookie cannot skip the passkey.
- Residual risks are named and bounded.

**Negative**
- New work across several components:
  - an SCA purpose and its linking;
  - a consent-service refusal;
  - a dedicated Keycloak flow, plus possibly a custom authenticator to withhold the code;
  - an app screen in `openbank-app`;
  - a new app-link path.
- Customers without a usable passkey cannot connect.
- Client-secret exceptions need owner tracking.
- The channel depends on ADR-0384 (open) and ADR-0320 P1/P2 (unbuilt).

**Neutral**
- ADR-0383 D4, D6 and D7 are unchanged.

**Enforcement.** No new gate. ADR-0383's rule that no route exists until the delivery checks hold
is the gate. `docs/threat-models/openbank-mcp-service.md` gains the tiers and D5 before any route
exists. Fixing `openbank-app` `http://localhost:*` is tracked in #16612.

### Delivery check

**Admission**
- Unauthenticated MCP `tools/list` → 401, no tools listed.
- An `openbank-app` token at MCP → 401.
- `resource` ≠ the mcp-service resource → `invalid_target`.
- Tier L `azp` without `cnf.jkt`, or without a proof → 401 (IT).
- Bearer token on a grant that has a `jkt` → 401.
- Tier H refresh without client authentication → `invalid_client`; with it → OK (local Keycloak).
- Rotated refresh token reused (confidential client) → the family is revoked.
- Rotating the client key or secret → existing grants still refresh.
- Realm assertion over `customers-realm-template.json`, for every MCP client: no direct grant,
  implicit grant, device grant or service account; exact redirects; `refreshTokenMaxReuse` = 0.

**PAR and authorize**
- `request_uri` reused, older than 60 s, used by another `client_id`, or with differing parameters
  → rejected.
- PKCE absent or `plain` → rejected.
- `iss` is present in the authorization response.
- ITs against a live Keycloak **with a valid SSO cookie**:
  - `prompt=none` → `login_required`, no code;
  - `prompt=create` → refused;
  - `kc_action` → no effect;
  - `max_age` omitted → a fresh WebAuthn assertion is still required;
  - `login_hint`/`kc_idp_hint` → no skip;
  - no silent-renew path issues a code.
- Realm and allowlist parity: Tier H ⇒ `publicClient=false` plus a client authenticator; Tier L ⇒
  DPoP-bound. A seeded mismatch fails the gate, and mcp-service refuses to boot.
- A client secret used after its exception expiry → `invalid_client`.
- The registration endpoint cannot be reached from an MCP authorize.
- A passkey or SCA device younger than 72 h → connect refused.

**SCA linking**
- `MCP_CLIENT_CONNECT` initiate without `approvalRequestId`/`payloadSha256` → 400.
- An approval for request A cannot release request B.
- Two concurrent flows → two challenges.
- consent-service rejects an `MCP_CLIENT_CONNECT` challenge as consent evidence.
- A `ConsumerScope.General` consumer trying to spend an `MCP_CLIENT_CONNECT` challenge → refused.
- `MCP_CLIENT_CONNECT` initiate succeeds, so the push message exists.
- Two concurrent polls on one auth session → exactly one code.
- A consume that succeeds while code issuance fails → no code, and a retry is refused.
- An auth session older than 300 s → no code.
- No code before the consume.
- An app denial → no code and an audited `DENIED`.

**Same device**
- A handle presented from another browser session → refused.
- A handle presented by another party's app → refused.
- The same-device flow without a fresh passkey assertion in the phone browser → no code.
- An `apple-app-site-association`/`assetlinks` check: the new path is claimed only by
  `tech.openbank.app` and is not matched by `/pay/v1*`.

**CIMD**
- If enabled: a URL off the allowlist, a redirect, or a private IP → refused, with no fetch
  recorded.
- A changed hash → the client is disabled until accepted.

**Throttling**
- The 4th pending authorization for a party → refused and audited.
- Per-client budget exceeded → 429 across that client's parties.
- Valkey down → calls are rejected.
- Suspend → the client's Keycloak tokens are revoked, its credential is disabled, and its next MCP
  call is denied.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is in scope of the authorize flow.
- DORA:    ICT third-party risk engagement — hosted platforms hold refresh tokens and client credentials, so each is allowlisted by tier, budgeted, monitored and suspendable.
- GDPR:    transparency and minimisation engagement — consent discloses that data goes to the platform; default tool limits bound what is transferred.
- PSD2:    SCA engagement — connecting a client requires full SCA in the bank app, dynamically linked to the authorization request; payments remain under ADR-0383 D6.
- CNB:     not applicable — no reporting or supervisory artefact changes.

## References

- ADR-0021, ADR-0065, ADR-0066, ADR-0086, ADR-0090, ADR-0095, ADR-0260, ADR-0320, ADR-0335, ADR-0383, ADR-0384 (#16607); issue #16612
- `openbank-sca-service/.../domain/model/ConsumerScope.kt` (`ReservedNamespace`, `ConsumerScope.General`), `ScaService.kt` (`buildPushMessage`, `STATIC_PUSH_MESSAGES`, `ttlSeconds = 300`)
- `openbank-sca-service/src/main/kotlin/com/openbank/sca/domain/model/DeviceApproval.kt`, `.../application/usecase/ScaService.kt` (`requireApprovalLinking`, `buildIdempotencyKey`)
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/application/usecase/ConsentService.kt`
- `openbank-infra/gitops/components/keycloak/customers-realm-template.json`, `keycloak.yaml`
- `openbank-infra/web/landing/.well-known/apple-app-site-association`
- RFC 10027 (BCP 247), RFC 9126, RFC 9449, RFC 8705, RFC 8707, RFC 9728, RFC 9207, RFC 8628, RFC 8252; W3C WebAuthn Level 3 (hybrid transport); MCP authorization specification 2026-07-28
