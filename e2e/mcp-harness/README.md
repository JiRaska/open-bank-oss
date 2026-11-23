<!-- SPDX-License-Identifier: Apache-2.0 -->
# MCP client harness and authorization attack suite

Layers 2 and 3 of the customer MCP channel test strategy (issue #16617, ADR-0383, ADR-0385).

- **Harness (layer 2)** — `node src/cli.ts --journey`: RFC 9728 discovery, PAR + PKCE S256 + RFC 8707
  `resource`, DPoP when the authorization server advertises ES256, a Playwright browser whose
  passkey ceremony is answered by a CDP WebAuthn virtual authenticator, the in-app SCA approval by a
  software device against sca-service's real device API, the RFC 9207 `iss` check, then
  `tools/list`, a read tool and `propose_payment` through the official MCP SDK client. The last
  step verifies a `PENDING` proposal receipt with its ID, amount and currency.
- **Attack suite (layer 3)** — `node src/cli.ts --run`: one case per ADR-0383/0385 negative delivery
  check a black-box client can observe, each asserting the exact expected outcome.

`node src/cli.ts --list` prints ACTIVE vs PENDING. ACTIVE cases run against today's server. PENDING
cases name the slice that builds their server-side mechanism. They are never reported as PASS.
`--include-pending` runs their bodies and reports `PENDING-WOULD-PASS` or `PENDING-FAILS`, so
moving a case to ACTIVE is a deliberate one-line edit. Exit codes: 1 = an ACTIVE case failed;
3 = an ACTIVE case was skipped for a missing precondition (a skip is not a pass).

`npm test` is the known-positive proof: every case with a body must pass against a correct stub and
fail against a stub with exactly the defence it targets switched off.

## Target guard

The harness forges tokens, replays proofs and approves SCA challenges. `src/guard.ts` is a
**safety net** against pointing it at a production bank by mistake. It is not a security
boundary. It refuses every target host unless the host is one of these:

- loopback, `*.localhost` or `*.test`;
- a labelled sandbox host: the **leftmost** label is `sandbox`, `staging`, `dev` or `qa`, **and**
  the host ends with a suffix listed in `MCP_HARNESS_ALLOWED_SUFFIXES` (comma-separated). The
  default list is empty, because no sandbox-labelled `open-bank.tech` host exists on main;
- exactly the host named by `MCP_HARNESS_I_UNDERSTAND_NON_LOCAL`.

The guard covers the resource URL, the configured and discovered authorization server and its
endpoints, the SCA URL, the `resource_metadata` URL a server names, every browser navigation,
and every redirect hop. Authenticated requests never follow a redirect. A URL with userinfo is
always refused.

The software SCA device self-approves challenges, so it is stricter. It accepts only loopback,
`*.localhost`, `*.test` or an allow-suffix sandbox host, and it has no override.

`--list --trace` maps every ADR-0383/0385 delivery-check line to its cases, or to the layer that
owns it (`src/delivery-checks.ts`; a test keeps the two in step).

## Running

```
npm ci
npx playwright-core install chromium        # journey and SSO-cookie cases only
MCP_HARNESS_RESOURCE_URL=http://localhost:8150/mcp node src/cli.ts --run
```

All configuration is read from the environment (`src/config.ts`): `MCP_HARNESS_RESOURCE_URL`,
`_AUTH_SERVER`, `_CLIENT_ID`, `_OTHER_CLIENT_ID`, `_CLIENT_SECRET`, `_REDIRECT_URI`, `_TIER`,
`_SCA_URL`, `_SCA_TOKEN`, `_PARTY_ID`, `_FOREIGN_TOKEN`, `_SESSION_FILE`, `_PASSKEY_FILE`, and
others. No deployed host is built in. The journey writes tokens and the DPoP key to
`MCP_HARNESS_SESSION_FILE` (mode 0600, `*.session.json` is gitignored) for the DPoP and refresh
cases.

The positive journey defaults to `https://localhost/customer/mcp` on a local HTTPS edge. Set
`MCP_HARNESS_RESOURCE_URL` to the deployed customer route in an isolated test environment.
Set `MCP_HARNESS_PROPOSE_ARGS` to a JSON object with the synthetic fixture's `debtorAccountId`,
`creditorIban`, `creditorName`, `amount`, `currency` and `clientRequestId` as strings, plus an
optional `reference`. Use an enrolled synthetic customer's granted account and a test creditor.
The journey stops when the proposal is pending. Payment approval and admission are outside this
command.

For a connect-and-read check in an isolated sandbox, run `npm run journey -- --read-only`.
This uses `list_accounts` by default and needs no proposal fixture or payment approval settings.
To check a downstream read, set `MCP_HARNESS_READ_TOOL=get_balance` or `list_transactions` and
provide the granted test account in the JSON object `MCP_HARNESS_READ_TOOL_ARGS`. This mode
completes the connect SCA and read, then stops before creating a payment proposal.

For unattended V2 Connect approval, set `MCP_HARNESS_SOFTWARE_CONNECT_APPROVAL=true` and
`MCP_HARNESS_SYNTHETIC_SANDBOX=true` with `--read-only`. Set `MCP_HARNESS_CUSTOMER_EDGE_URL`
to the sandbox base ending in `/customer/v1`, `MCP_HARNESS_APP_TOKEN` to the synthetic customer's
first-party app token and `MCP_HARNESS_PARTY_ID` to that customer. Set
`MCP_HARNESS_CONNECT_CREDENTIAL_ID` and `MCP_HARNESS_CONNECT_PRIVATE_KEY_FILE` to the existing
synthetic SCA credential and its local P-256 PEM file (mode 0600). The device must already satisfy
the bank's 72-hour enrollment floor. Use `MCP_HARNESS_READ_TOOL=get_balance` with the synthetic
`accountId` in `MCP_HARNESS_READ_TOOL_ARGS`. The harness selects balance access for that one
account, checks the V2 context against the journey's PAR and app's pending SCA challenge, then
signs the standard app decision. This mode requires PAR and DPoP and disallows target overrides
and session files. It can be combined with `--verify-revocation`.

Run `npm run journey -- --verify-proposal` to verify V2 proposal creation with the same synthetic
software device and Customer Edge settings. Keep the scope at `openid` for online authorization.
Provide `MCP_HARNESS_PROPOSE_ARGS` with the synthetic instruction described above, using the same
debtor account as `MCP_HARNESS_READ_TOOL_ARGS`. This mode consents to balance and proposal access
for that one account, checks the actual balance, then creates a `PENDING` proposal. It reads the
same pending, unexecuted instruction from the owner's app endpoint and stops there.

In an isolated synthetic fixture, append `--verify-revocation` to verify that the new connection can
be revoked. Set `MCP_HARNESS_SYNTHETIC_SANDBOX=true`, `MCP_HARNESS_READ_TOOL=get_balance` and provide
the granted account ID in `MCP_HARNESS_READ_TOOL_ARGS`, its currency in
`MCP_HARNESS_VERIFY_BALANCE_CURRENCY`, and the matching customer's existing app token and sandbox
edge base in `MCP_HARNESS_APP_TOKEN` and `MCP_HARNESS_CUSTOMER_EDGE_URL` (ending at `/customer/v1`).
This mode needs a V2 DPoP connection and rotating refresh tokens. It reads the expected balance,
proves the refresh family works, revokes the one connection through the app's own endpoint, then
requires an explicit grant refusal with the same access token and a refresh refusal with the
rotated token. The mode disallows target overrides and session files.

## Checks deliberately not here

These belong to other layers: the `opa eval` bundle checks (ADR-0383 D4), the `MCP_CLIENT`
allow-list unit test, the realm and allowlist parity gate, the throttle-ordering IT, Valkey-down
and other chaos cases (layer 7), the same-device app-link checks (layer 9) and CIMD.


## Observe sandbox app approval

For the complete payment journey, set `MCP_HARNESS_CUSTOMER_EDGE_URL` to the isolated
sandbox edge base ending in `/customer/v1`, and `MCP_HARNESS_APP_TOKEN` to the same synthetic
customer's existing bank-app session token. Both the MCP and customer-edge servers must pass
the sandbox-only target rule. The token stays in memory and is sent only to the customer edge.

The complete journey first requires a downstream balance for the synthetic debtor account
and requested currency. It then reads the proposal from the app API and confirms it is pending with no
payment ID. Follow the prompt to review and approve the sandbox proposal in the app with SCA.
The harness uses only GET requests to observe the proposal. It passes this step when the
proposal reaches `EXECUTED` with a payment ID, which proves initiation and does not establish
settlement. Omit both observation variables to run only through the pending proposal.
