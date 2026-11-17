<!-- SPDX-License-Identifier: Apache-2.0 -->
# MCP client harness and authorization attack suite

Layers 2 and 3 of the customer MCP channel test strategy (issue #16617, ADR-0383, ADR-0385).

- **Harness (layer 2)** — `node src/cli.ts --journey`: RFC 9728 discovery, PAR + PKCE S256 + RFC 8707
  `resource`, DPoP when the authorization server advertises ES256, a Playwright browser whose
  passkey ceremony is answered by a CDP WebAuthn virtual authenticator, the in-app SCA approval by a
  software device against sca-service's real device API, the RFC 9207 `iss` check, then
  `tools/list`, a read tool and `propose_payment` through the official MCP SDK client.
- **Attack suite (layer 3)** — `node src/cli.ts --run`: one case per ADR-0383/0385 negative delivery
  check a black-box client can observe, each asserting the exact expected outcome.

`node src/cli.ts --list` prints ACTIVE vs PENDING. ACTIVE cases run against today's server. PENDING
cases name the slice that builds their server-side mechanism. They are never reported as PASS.
`--include-pending` runs their bodies and reports `PENDING-WOULD-PASS` or `PENDING-FAILS`, so
moving a case to ACTIVE is a deliberate one-line edit. Exit codes: 1 = an ACTIVE case failed;
3 = an ACTIVE case was skipped for a missing precondition (a skip is not a pass).

`npm test` is the known-positive proof: every case with a body must pass against a correct stub and
fail against a stub with exactly the defence it targets switched off.

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

## Checks deliberately not here

These belong to other layers: the `opa eval` bundle checks (ADR-0383 D4), the `MCP_CLIENT`
allow-list unit test, the realm and allowlist parity gate, the throttle-ordering IT, Valkey-down
and other chaos cases (layer 7), the same-device app-link checks (layer 9) and CIMD.
