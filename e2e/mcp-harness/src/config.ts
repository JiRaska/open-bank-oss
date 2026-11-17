// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Every target is configured through the environment. The only default is the local
// openbank-mcp-service port (8150, its application.yaml); no deployed host is baked in.

export interface HarnessConfig {
  /** The MCP endpoint (the RFC 8707 resource). */
  resourceUrl: string
  /** Optional override; otherwise taken from RFC 9728 protected-resource metadata. */
  authServerUrl?: string
  clientId?: string
  /** A second allowlisted client id, for the `request_uri` cross-client attack. */
  otherClientId?: string
  /** Tier H confidential clients only (ADR-0385 D1). Never logged. */
  clientSecret?: string
  /** RFC 8252 loopback redirect. `localhost` is deliberately not the default (ADR-0385 D1). */
  redirectUri: string
  scope: string
  /** Tier from the bank's allowlist declaration, never from a token (ADR-0385 D1). */
  tier: 'L' | 'H'
  /** sca-service base URL for the software SCA device; absent ⇒ manual/pluggable approver. */
  scaUrl?: string
  /** Customer-realm token the SCA device uses to list and decide its own party's challenges. */
  scaToken?: string
  partyId?: string
  /** A token minted for the bank's own app client (`openbank-app`), for the audience attack. */
  foreignToken?: string
  /** Where the journey writes tokens + DPoP key for the attack suite. Mode 0600, never committed. */
  sessionFile?: string
  /** JSON file with a WebAuthn credential to inject into the virtual authenticator. */
  passkeyFile?: string
  readTool: string
  readToolArgs: Record<string, unknown>
  proposeArgs: Record<string, unknown>
  headless: boolean
  timeoutMs: number
}

const json = (raw: string | undefined, fallback: Record<string, unknown>): Record<string, unknown> =>
  raw ? (JSON.parse(raw) as Record<string, unknown>) : fallback

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HarnessConfig {
  const tier = (env.MCP_HARNESS_TIER ?? 'L').toUpperCase()
  if (tier !== 'L' && tier !== 'H') throw new Error(`MCP_HARNESS_TIER must be L or H, got ${tier}`)
  return {
    resourceUrl: env.MCP_HARNESS_RESOURCE_URL ?? 'http://localhost:8150/mcp',
    authServerUrl: env.MCP_HARNESS_AUTH_SERVER || undefined,
    clientId: env.MCP_HARNESS_CLIENT_ID || undefined,
    otherClientId: env.MCP_HARNESS_OTHER_CLIENT_ID || undefined,
    clientSecret: env.MCP_HARNESS_CLIENT_SECRET || undefined,
    redirectUri: env.MCP_HARNESS_REDIRECT_URI ?? 'http://127.0.0.1:53682/callback',
    scope: env.MCP_HARNESS_SCOPE ?? 'openid offline_access',
    tier,
    scaUrl: env.MCP_HARNESS_SCA_URL || undefined,
    scaToken: env.MCP_HARNESS_SCA_TOKEN || undefined,
    partyId: env.MCP_HARNESS_PARTY_ID || undefined,
    foreignToken: env.MCP_HARNESS_FOREIGN_TOKEN || undefined,
    sessionFile: env.MCP_HARNESS_SESSION_FILE || undefined,
    passkeyFile: env.MCP_HARNESS_PASSKEY_FILE || undefined,
    readTool: env.MCP_HARNESS_READ_TOOL ?? 'list_accounts',
    readToolArgs: json(env.MCP_HARNESS_READ_TOOL_ARGS, {}),
    proposeArgs: json(env.MCP_HARNESS_PROPOSE_ARGS, {
      // The schema McpToolRegistry advertises today; fromAccountId is target-specific.
      fromAccountId: env.MCP_HARNESS_FROM_ACCOUNT_ID ?? '',
      toIban: 'CZ6508000000192000145399',
      amount: '1.00',
      currency: 'CZK',
    }),
    headless: env.MCP_HARNESS_HEADED !== 'true',
    timeoutMs: Number(env.MCP_HARNESS_TIMEOUT_MS ?? 120_000),
  }
}
