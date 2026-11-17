---
date: 2026-11-17
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [ai-agents, authn, security]
summary: "Amends ADR-0383 D2/P3: hosted MCP clients may use bearer tokens only as authenticated confidential clients; local clients need DPoP. Authorize = passkey in the starting browser (hybrid QR+BLE) plus SCA in the app."
---

# ADR-0385 — Customer MCP client admission tiers and phishing-resistant cross-device authentication at the authorize endpoint

## Context

**This ADR amends ADR-0383 (accepted on main as `proposed/planned`) on two points and nothing
else.** It does not supersede it, so `supersedes` stays empty; the amendment is stated here and in
the Decision, following the precedent of ADR-0260, which amends ADR-0006 the same way. SCHEMA.md
has no field for a partial amendment.

1. **ADR-0383 D2 and P3 say "no bearer-token fallback" for every client.** Measured against the
   current MCP authorization specification and the published connector documentation of the hosted
   assistant platforms, no major MCP host is verified to support DPoP in its OAuth client. Hosted
   assistant platforms run the OAuth client on their own servers, with fixed HTTPS callback URLs,
   and offer confidential-client authentication (a key-signed client assertion against a published
   JWKS, or a platform mTLS client certificate). Local MCP clients use loopback or custom-scheme
   redirects as public clients. Applied unchanged, ADR-0383 admits no hosted client at all. The
   risk DPoP addresses is a stolen token being replayed by someone else. For a hosted confidential
   client that risk can also be bounded by binding the refresh token to the platform's client
   credential. For a local public client nothing else binds the token, and the MCP specification
   notes that client metadata documents cannot stop localhost impersonation. So the two kinds of
   client need different rules.
2. **ADR-0383 D1 says only that the connect screen is "rendered by the bank and requires SCA".**
   It does not say how the customer authenticates in the browser where the assistant opened the
   authorize URL. That browser is usually a computer, and the customer's credential is usually on
   the phone. This is a cross-device flow. RFC 10027 (BCP 247, *Security of Cross-Device Flows*,
   Aug 2026) treats cross-device flows as phishing-prone. It recommends a risk assessment,
   proximity where possible, short-lived single-use codes, displayed context, rate limits against
   consent flooding, sender-constrained tokens and authenticate-then-initiate. It allows the device
   grant only as a last resort and not for high-value resources. It recommends FIDO2/WebAuthn
   hybrid with BLE proximity.

**Adjacent decisions and why they do not cover this.**
- ADR-0383: the channel itself. This ADR amends D2/P3 and fills in the authentication part of D1.
- ADR-0065: the customers realm with a public PKCE client for the bank's own app. It does not cover
  third-party clients.
- ADR-0066: passkey-first customer login, with a login key separate from the SCA key. It decides
  the factor, not how it is used across devices for a third-party OAuth flow. This ADR reuses it.
- ADR-0021 and ADR-0086: decoupled device approval and single-use challenge consume. These are
  the SCA machinery. `ScaPurpose.CONSENT_GRANT` already exists in
  `openbank-sca-service/.../domain/model/ScaChallenge.kt`.
- ADR-0095 (QRlessPay): proves the app has BLE capability. Its proximity is payee-to-payer
  descriptor transfer, not authentication. It is cited for capability only and is not reused as a
  protocol.
- ADR-0320 P2: a DPoP proof check **and** an mTLS `cnf.x5t#S256` check as one primitive. P1 is the
  egress allowlist. Both are planned and not built (#10928).
- ADR-0384 (open as #16607, not yet on main): channel observability, abuse signals and the
  per-client kill switch / suspend (its D4). This ADR relies on those signals and that switch. If
  ADR-0384 changes before it merges, the references here follow it.
- ADR-0090: TPPs use XS2A, which remains out of scope.

**Deadline.** No regulatory date applies. The binding order is ADR-0383's: no route to
mcp-service exists until every prerequisite, including the amended P3 below, holds.

## Decision

We will admit MCP clients in two tiers, and we will authenticate the customer at the authorize
endpoint with a passkey in the browser that started the flow, followed by an SCA confirmation in
the bank app.

**D1 — Client admission tiers (amends ADR-0383 D2 and P3 for Tier H only)**

*Common to both tiers:*
- Clients are allowlisted and pre-registered. Dynamic client registration stays off.
- Client ID Metadata Documents (CIMD) are accepted only from a metadata URL on the allowlist. The
  authorization server fetches them itself through the ADR-0320 egress allowlist. It stores the
  content hash, and a changed document is not used until a reviewer accepts the new hash. It never
  fetches an arbitrary URL, because that would be an SSRF and client-impersonation path.
- Every client uses authorization code + PKCE S256. Tokens carry the RFC 8707 resource /
  `aud` = `openbank-mcp-service`. The authorization server publishes RFC 9728 protected-resource
  metadata and RFC 9207 `iss` in the authorization response. Redirects match exactly.
- There is never a device grant, an implicit grant or a password grant, and MCP never passes a
  token through to a downstream service. This is unchanged from ADR-0383 D1/D4.
- Scope is read + propose only (ADR-0383 D3/D4). Access tokens live at most 5 minutes. Refresh
  tokens rotate, and reuse of a rotated refresh token revokes the whole family.

*Tier H — hosted, vendor-run confidential client:*
- The client must have a fixed HTTPS redirect on an allowlisted host.
- It must authenticate as a confidential client at **both** the token and refresh endpoints:
  - preferred: `private_key_jwt` against an allowlisted JWKS URL, or an mTLS client certificate
    where the platform offers one (RFC 8705; the token then carries `cnf.x5t#S256`, and MCP checks
    it with the ADR-0320 P2 primitive);
  - minimum: a pre-registered client secret issued per bank, at least 256 bits, rotated at least
    every 90 days and never logged.
- A Tier H client without client authentication at token and refresh is not admitted. The refresh
  token is therefore useless without the platform's credential.
- Under those conditions the access token may be a **Bearer** token. This is the only exception to
  ADR-0383's "no bearer fallback". When the client supports DPoP it is used, and the grant then
  records the `jkt` as ADR-0383 D3 requires.
- A Tier H grant records the client identity in place of a `jkt`. The ADR-0383 D3 lookup key
  becomes `(party, azp, jkt | client-credential id)`.

*Tier L — local public client (loopback or custom-scheme redirect):*
- DPoP is **required**. A local client without DPoP is not admitted, because localhost
  impersonation makes an unbound public-client token unacceptable.
- ADR-0383 D2 applies to Tier L unchanged.

*Amended ADR-0383 P3:* the deployed Keycloak version is verified against a local instance of that
exact version for these checks:
1. DPoP-bound access and refresh tokens for a Tier L public client;
2. confidential-client authentication enforced at the refresh endpoint for a Tier H client
   (`private_key_jwt` and client secret, and mTLS where it is configured);
3. PAR required (D2);
4. the D2 authentication flow;
5. the ADR-0383 D4 exchange.

If any check fails, the channel does not open.

**D2 — Authentication and consent at the authorize endpoint**
1. **PAR is required** (RFC 9126). The authorization request is held server-side and cannot be
   tampered with. The `request_uri` is single-use and lives at most 60 s.
2. **Authentication is a passkey (WebAuthn, ADR-0066) in the browser that started the flow.**
   - If the passkey is on the phone, the OS offers WebAuthn hybrid: the customer scans the
     platform's QR with the phone camera, and the protocol enforces BLE proximity. The assertion is
     bound to the bank's origin.
   - This is the "QR sign-in" user experience. There is no bank-made QR.
   - If no passkey is usable in that browser, the flow stops. There is no password, SMS, e-mail
     link or device code fallback.
   - This is authenticate-then-initiate: the customer is authenticated before any consent is
     shown.
3. **Consent** is a bank-rendered screen in that browser **and** a confirmation in the bank app.
   - The app is reached by push, which is the ADR-0021 decoupled approval with
     `ScaPurpose.CONSENT_GRANT`.
   - Both screens show the same bank-produced context:
     - client name;
     - redirect hostname;
     - capabilities, stated as "read your data and propose payments; it can never pay by itself";
     - that data leaves the bank to the platform's own provider;
     - expiry;
     - approximate location and device where the flow started, with a warning when it differs from
       the app's.
   - The app approval is full SCA, and the challenge is consumed single-use (ADR-0086).
   - The authorization code is issued only after that consume. The code is single-use and lives at
     most 60 s.
4. **Same-device** (the assistant runs on the phone): the flow switches app-to-app to the bank
   app, which shows the same confirmation with SCA. No QR is involved (RFC 10027: no cross-device
   flow on one device). The redirect and app-switch link is a bank-only verified app link, a new
   path claimed only by `tech.openbank.app`. It is **not** the shared `/pay/v1*` path, which other
   apps also claim (ADR-0095 pilot,
   `openbank-infra/web/landing/.well-known/apple-app-site-association`). The web fallback page only
   tells the customer to install the app.
5. **Consent-phishing controls.** An attacker can start a connect in their own assistant account
   and send the victim the authorize link. These controls apply to that case:
   - an in-flow warning: "approve only if you just pressed Connect in your own assistant";
   - a new-connection push, plus a 24-hour banner in the app with one-tap revoke;
   - per-party rate limits on pending authorization requests (at most 3 open) and on new
     connections (at most 5 per day). A breach is refused and audited;
   - the ADR-0384 abuse indicator "new connection followed by bulk reads".
6. **Data minimisation.** By default tools return bounded data: a transaction window of 90 days,
   page size of at most 50 and statement ranges of at most 12 months, under ADR-0383 D5. This
   bounds what a prompt-injected model can move out through the platform's other tools.
7. **Platform-compromise bounds.** A hosted platform holds refresh tokens and client credentials
   for every customer connected through it. Controls:
   - a per-client aggregate read budget in Valkey, failing closed like ADR-0383 D5;
   - a per-client anomaly alert (ADR-0384);
   - the per-client emergency suspend (ADR-0384 D4).

   The consent screen says that data goes to the platform.

**RFC 10027 §6.1 mapping**

| Category | Mitigation here |
|---|---|
| Establish proximity | WebAuthn hybrid BLE (D2.2); same-device app switch (D2.4) |
| Short-lived / single-use | PAR `request_uri` and code ≤ 60 s single-use; SCA challenge single-use (D2.1, D2.3) |
| Request binding / context display | PAR-held request; identical bank-rendered context in browser and app; origin-bound assertion (D2.2–3) |
| Authenticate-then-initiate | passkey before consent (D2.2) |
| Rate limits vs consent flooding | per-party pending/new-connection caps (D2.5) |
| Sender-constrained tokens | DPoP for Tier L; credential-bound refresh for Tier H, DPoP when offered (D1) |
| Device grant | never (D1) |
| User education / notification | in-flow warning, new-connection push, 24 h banner, revoke (D2.5) |

**Accepted residual risk** (bounded by read + propose only, ADR-0383 D4/D6):
- A victim who completes a flow the attacker started. The passkey does not stop this; D2.5 detects
  and limits it.
- Malware on the computer or phone that the flow runs on.
- A compromised hosted platform, which can read within the D2.7 budget until it is suspended.
- Exfiltration by a prompt-injected model of what the D2.6 defaults allow.

## Alternatives considered

- **Bank-rendered QR plus a 2-digit matching code as the authentication step, without proximity.**
  Good UX and no OS dependency. Rejected: QRLJacking relays the live QR **and** the matching code
  to the attacker's page, so number matching does not stop it (RFC 10027 notes that bound data can
  be phished). WebAuthn hybrid gives the same scan experience with origin binding and enforced
  proximity.
- **Keep ADR-0383 D2 unchanged (DPoP for everyone).** Simplest rule. Rejected: no hosted platform
  is verified to send DPoP, so the channel would serve almost no one, and a confidential client
  with a credential-bound refresh token achieves the replay bound by another route.
- **Admit hosted clients as public clients (no client authentication).** Rejected: a leaked
  refresh token would be usable by anyone.
- **Dynamic client registration.** Rejected: deprecated by the MCP specification, and it defeats
  the allowlist.
- **Device authorization grant (RFC 8628).** Rejected: RFC 10027 allows it only as a last resort
  and not for high-value resources. It is the canonical consent-phishing grant.
- **Admit local clients without DPoP.** Rejected: localhost impersonation (above).
- **Password, SMS or e-mail-link fallback when no passkey is available.** Rejected: phishable, and
  ADR-0066 creates no password.

## Consequences

**Positive**
- Hosted assistants can connect without weakening the replay bound.
- Cross-device authentication is phishing-resistant by protocol, not by user vigilance.
- Remaining risks are named, detected and bounded.

**Negative**
- Customers without a usable passkey cannot connect.
- A Tier H client secret is a shared secret and needs rotation operations.
- Keycloak must support PAR, refresh-endpoint client authentication and the D2 flow at the
  deployed version (amended P3).
- The app confirmation screen lives in `openbank-app`.
- The D2.5 and D2.7 controls depend on ADR-0384, which is still open.

**Neutral**
- ADR-0383 D3–D7 are unchanged apart from the D3 lookup key.

**Enforcement.**
- No new gate. The controls are runtime and are verified by the ITs and realm checks below. The
  ADR-0383 rule that no route exists until the delivery checks hold is the gate.
- `docs/threat-models/openbank-mcp-service.md` gains the two tiers and the residual risks before
  any route exists.

### Delivery check
- **Tier L without DPoP:** an IT over real HTTP shows that a Tier L access token presented without
  a DPoP proof gets 401.
- **Tier H refresh without client authentication:** against a local Keycloak of the deployed
  version, a refresh with a Tier H refresh token and no client assertion, certificate or secret
  gets `invalid_client`. The same request with the credential succeeds.
- **PAR required:** an authorization request without `request_uri` is rejected.
- **PAR reuse and expiry:** a reused `request_uri` is rejected, and a `request_uri` older than 60 s
  is rejected.
- **No code before the app:** no authorization code is issued until the `CONSENT_GRANT` challenge
  is consumed. A second consume of that challenge is refused.
- **Wrong context:** an app denial produces no code and an audited `DENIED` decision.
- **Consent-flood cap:** the 4th pending authorization request for one party is refused and
  audited.
- **No fallback:** the customers-realm browser flow for MCP clients contains no password, OTP or
  device-code step. A check over `customers-realm-template.json` lists only the WebAuthn
  authenticator for that flow.
- **CIMD allowlist:** a client presenting a metadata URL off the allowlist is refused, and no
  outbound fetch is recorded.
- **Per-client budget:** an IT shows that exceeding the per-client aggregate read budget gives 429
  across all parties of that client.

## Compliance impact

- PCI DSS: not applicable — no cardholder data is in scope of the authorize flow.
- DORA:    ICT third-party risk engagement — hosted platforms hold refresh tokens and client credentials, so each platform is allowlisted, suspendable and monitored per client.
- GDPR:    transparency engagement — the consent screen discloses that data leaves the bank to the platform; default tool limits minimise what is transferred.
- PSD2:    SCA engagement — connecting a client requires full SCA in the bank app with single-use challenge consumption; payments remain under ADR-0383 D6.
- CNB:     not applicable — no reporting or supervisory artefact changes.

## References

- ADR-0021, ADR-0065, ADR-0066, ADR-0086, ADR-0090, ADR-0095, ADR-0260, ADR-0320, ADR-0383, ADR-0384 (#16607)
- `openbank-sca-service/src/main/kotlin/com/openbank/sca/domain/model/ScaChallenge.kt` (`ScaPurpose.CONSENT_GRANT`)
- `openbank-infra/gitops/components/keycloak/customers-realm-template.json`
- `openbank-infra/web/landing/.well-known/apple-app-site-association`
- RFC 10027 (BCP 247), RFC 9126 (PAR), RFC 9449 (DPoP), RFC 8705 (mTLS), RFC 8707, RFC 9728, RFC 9207, RFC 8628, RFC 8252; MCP authorization specification 2026-07-28
