# Assistant platform packages for the customer MCP channel

Packages that put the OpenBank customer MCP channel (ADR-0383, client admission in ADR-0385) into
the two largest hosted assistant platforms' official listings, so a customer never types the MCP
URL by hand. Issue #16614, part of #16590.

> **Status: not connectable today.** No route to `openbank-mcp-service` exists, and ADR-0383 says
> none is created until all its prerequisites have landed. The packages are complete and they
> validate, but `scripts/check.py --release` refuses to call them publishable until the
> preconditions below hold and the owner-only values are filled in.

This directory is **not a released component**. It has no `version.txt` and is not registered with
release-please. `packageVersion` in `listing.json` is the package's own version for the vendor
listings.

## Layout

```
listing.json                 SINGLE SOURCE: name, logo, the one MCP URL, scopes, admission, tools
assets/icon.svg, icon.png    the admin-ui brand mark (Sidebar.tsx), rendered at 512 px; no new brand
scripts/generate.py          renders packages/ from listing.json (packages/ is never hand-edited)
scripts/check.py             tool drift + structure + credential checks; --release; --selftest
packages/
  openai-plugin/             ZIP this folder for "Upload plugin archive" (ChatGPT + Codex)
    plugin.json, mcp.json            Agent Plugins format (root manifest + MCP config)
    .codex-plugin/plugin.json, .mcp.json   Codex compatibility manifest
    assets/icon.png, logo.png, logo.svg
  claude-code-plugin/        Claude Code plugin (.claude-plugin/plugin.json + .mcp.json)
  .claude-plugin/marketplace.json   marketplace that lists the Claude Code plugin
  claude-connector-listing/  answers for the Claude Connectors Directory portal form (not a file upload)
```

The display name, the logo and the MCP URL exist **once**, in `listing.json`. The consent screen and
the app show a bank-assigned name and logo (ADR-0385 D2.3), so the customers-realm client entries
must take theirs from this file as well when they are built. `check.py` fails if any package
carries a second URL, name or icon.

## Validate

```
python3 integrations/assistant-plugins/scripts/generate.py --check   # packages/ == listing.json render
python3 integrations/assistant-plugins/scripts/check.py              # drift + structure, exit 1 on finding
python3 integrations/assistant-plugins/scripts/check.py --selftest   # 15 sabotage cases, each must be caught
python3 integrations/assistant-plugins/scripts/check.py --release    # also fails on placeholders / no route
claude plugin validate integrations/assistant-plugins/packages/claude-code-plugin
claude plugin validate integrations/assistant-plugins/packages
```

- **Tool drift.** `check.py` parses `McpToolRegistry.kt` and checks the capability, the input
  properties and the required arguments of every packaged tool. It fails when a registry tool is
  neither included nor excluded, and when a packaged capability is outside the ADR-0383 D4
  customer-channel set (`query.*.readonly`, `propose.payment`). The customer-facing descriptions
  live in `listing.json`, because the registry's current text describes the governed-agent PSD2
  consent path, which this channel does not use (ADR-0383 D3). Excluded: `list_consents` (D3) and
  `count_marketing_consents` (an operator aggregate).
- **No vendor schema is vendored.** The agent-plugins.org schema carries no licence statement, and
  Anthropic publishes reference pages, not a schema file. `check.py` checks the documented rules
  structurally, with the limits cited inline. For Claude Code, `claude plugin validate` is the
  vendor's own validator. It was run against both directories, and also against a deliberately
  broken `url`, which it rejected.
- **Not a CI gate.** The check runs only as a script today. Registering it would need a
  `gates.yaml` declaration plus a budget and a known-positive under `.github/CLAUDE.md`'s rules,
  and it protects nothing that ships until the channel has a route. Register it in the slice that
  creates the route.

## Admission: which path the bank supports (ADR-0385 D1)

The bank admits only two kinds of client: a **Tier H** hosted client that authenticates as a
confidential client at **both** the token and the refresh endpoint, and a **Tier L** local client
that uses **DPoP**. Dynamic client registration is off. A CIMD is accepted only from an allowlisted
metadata URL, with a reviewed content hash.

| Platform path | Client identity | Verdict |
|---|---|---|
| ChatGPT, published directory app (this ZIP) | CIMD `https://chatgpt.com/oauth/client.json`, `private_key_jwt` with JWKS `https://chatgpt.com/oauth/jwks.json`, redirect `https://chatgpt.com/connector_platform_oauth_redirect` | **Tier H candidate (preferred form).** Bank side not built |
| Claude Connectors Directory listing | `oauth_anthropic_creds`: a confidential client the owner creates and enters in the portal; redirect `https://claude.ai/api/mcp/auth_callback` | **Tier H candidate at the ADR-0385 minimum** (client secret ≥ 256 bits, rotated ≤ 90 days). Bank side not built |
| Claude custom connector added by URL | Anthropic's CIMD as a public client, or DCR; the portal secret is never used | **Not supported by the bank** |
| Claude Code plugin (`claude-code-plugin/`) | Claude Code's own CIMD, public client, loopback redirect; DPoP not documented | **Not supported by the bank** until DPoP is verified (Tier L) |
| ChatGPT / Codex custom MCP server typed by URL, `token_endpoint_auth_method: none` | public client | **Not supported by the bank** |
| Codex local CLI using this plugin | not verified whether it uses the hosted confidential client or a local public one | **Not supported** until verified |

The Claude Code plugin is still shipped, so the listing exists the day Tier L is verified. Its
`.mcp.json` pins no `clientId` and carries no secret.

## Preconditions for the demo (none hold today)

From ADR-0383 and ADR-0385. Each one must be live before a single step below can work:

1. ADR-0383 P1: DPoP proof verifier and shared replay store (ADR-0320 primitive). Needed for Tier L
   only, but also for DPoP whenever a Tier H platform offers it.
2. ADR-0383 P2: the `MCP_CLIENT` grantee type in delegation-service.
3. ADR-0385's amended P3: Keycloak verified against a local instance of the deployed version for
   Tier H confidential-client auth at refresh, PAR, the passkey authorize flow and the D4 exchange.
4. ADR-0383 P4: the Valkey connection for mcp-service (replay store, nonces, buckets; fails closed).
5. The ADR-0383 D1 customer tenant in `CallerContextResolver`, and D4 token exchange per read.
6. The ADR-0383 D6 proposal store, plus the customer approval surface in customer-edge and the app.
   Until then `propose_payment` is refused.
7. ADR-0385 D2: PAR, passkey in the starting browser, consent in browser **and** app with
   `ScaPurpose.CONSENT_GRANT`.
8. A route to mcp-service at the `listing.json` URL, the RFC 9728 document at
   `server.protectedResourceMetadata`, and a `401` with `WWW-Authenticate: Bearer resource_metadata=...`.
9. Realm clients for the Tier H platforms, with the scope names in `listing.json` defined.
   They are proposed there and do not exist yet.

## Demo script (target behaviour once the preconditions hold)

1. **Connect.** In ChatGPT's or Claude's connector directory, open **OpenBank** and press Connect.
   The customer types no URL. The platform reads the RFC 9728 metadata and sends a PAR-backed
   authorize request to the customers realm.
2. **Authenticate.** The bank page asks for a passkey in that browser. If the passkey is on the
   phone, the OS shows its own QR and the phone checks BLE proximity. There is no password, SMS or
   device-code fallback.
3. **Consent.** The bank page and the bank app both show the same context: client name, redirect
   host, "read your data and propose payments; it can never pay by itself", that data goes to the
   platform's provider, the expiry, and where the flow started. The customer approves in the app
   with full SCA. The app then shows a new-connection notice with one-tap revoke.
4. **Ask the balance.** "What is the balance of my main OpenBank account?" The assistant calls
   `list_accounts`, then `get_balance`. The customer sees the balance, with identifiers masked.
5. **Propose a payment.** "Propose paying 25 EUR to <IBAN>, reference rent." The assistant calls
   `propose_payment` and answers that a proposal was created and **no money has moved**.
6. **Approve in the app.** The app shows the proposal labelled as coming from the named assistant,
   with only bank-produced data: the IBAN, the Verification of Payee result (or "unverified"), the
   amount and the currency. The customer approves it individually with SCA and dynamic linking. No
   exemption applies, and the payee is not added to trusted beneficiaries.
7. **What the customer never sees:** a way to make the assistant pay, raise a limit, add a trusted
   payee or change security settings. The negative review cases in `plugin.json` test exactly that.

## Security notes (what ADR-0383/0385 decide, no more)

- **Read + propose only.** The assistant cannot authorise anything. A proposal executes only after
  SCA in the bank app (ADR-0383 D6). This is enforced at MCP's PDP and at every downstream PDP
  (D4), not by the model.
- **Bearer tokens only for Tier H**, and only with confidential-client authentication at token and
  refresh. The refresh token is useless without the platform's credential. DPoP is used when a
  platform supports it. Access tokens live ≤ 5 minutes and refresh tokens rotate (ADR-0385 D1).
- **Revocation** takes effect on the next call, because every call re-reads the delegation grant
  (ADR-0383 D2/D3).
- **Accepted residual risks** (ADR-0385): a victim who completes a flow that an attacker started
  (detected and rate-limited, not prevented), malware on the user's devices, a compromised hosted
  platform (bounded by a per-client budget and suspend), and exfiltration by a prompt-injected model
  of whatever the bounded defaults allow (90 days of transactions, 50 per page, 12 months of
  statements).
- **What these packages do NOT provide:** they carry no credentials and no client identity. A
  listing is a discovery and anti-typosquatting control. It is not an authorisation control. Every
  control above is server-side and not built yet.

## Owner-only steps

- Fill in the `OWNER-SUPPLIED` values in `listing.json`: legal entity, support page and contact,
  privacy policy and terms URLs. Then run `generate.py`. The privacy policy must cover collection,
  use and storage, third-party sharing (the platform's provider), retention and contact.
- Create the Tier H realm clients. ChatGPT: an allowlisted CIMD with `private_key_jwt`. Claude: a
  confidential client whose ID and secret the owner enters in the Anthropic developer portal, and
  never in this repository.
- Record the demo video for OpenAI review, and provide the reviewer test account through the
  vendor dashboard, not in the ZIP.
- Submit. ZIP `packages/openai-plugin/` for the OpenAI dashboard. Fill in the Claude portal form
  from `packages/claude-connector-listing/directory-listing.json` and acknowledge the compliance
  items, including financial transactions. No agent submits anything.

## Sources (vendor docs, read 2026-11-17)

- OpenAI plugin build guide: https://developers.openai.com/plugins/build/plugins
- OpenAI submission: https://developers.openai.com/plugins/deploy/submission
- OpenAI authentication: https://developers.openai.com/plugins/build/auth
- Agent Plugins MCP schema: https://agent-plugins.org/schemas/1.0.0/mcp.schema.json
- Claude Code plugin manifest: https://code.claude.com/docs/en/plugins-reference
- Claude Code marketplace: https://code.claude.com/docs/en/plugins/marketplace-reference
- Claude Code MCP (OAuth, RFC 9728, CIMD): https://code.claude.com/docs/en/mcp
- Claude connector authentication: https://claude.com/docs/connectors/building/authentication
- Claude connector submission: https://claude.com/docs/connectors/building/submission
