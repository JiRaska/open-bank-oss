// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Every target is configured through the environment. Defaults point to loopback;
// no deployed host is baked in.

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
  approvalObservation?: { edgeUrl: string; appToken: string }
  headless: boolean
  timeoutMs: number
}

export interface JourneyConfig extends HarnessConfig {
  readOnly: boolean
  revocationVerification?: RevocationVerification
  softwareConnectApproval?: SoftwareConnectApproval
  verifyProposal?: boolean
  appSignIn?: boolean
  appCounterFile?: string
}

export interface SoftwareConnectApproval {
  edgeUrl: string
  appToken: string
  partyId: string
  accountId: string
  credentialId: string
  keyFile: string
}

export interface RevocationVerification {
  edgeUrl: string
  appToken: string
  accountId: string
  currency: string
}

const READ_ONLY_TOOLS = new Set(['list_accounts', 'get_balance', 'list_transactions'])
const FIXTURE_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const json = (raw: string | undefined, fallback: Record<string, unknown>): Record<string, unknown> =>
  raw ? (JSON.parse(raw) as Record<string, unknown>) : fallback

import { assertScaTarget, assertTarget, isLocalOrTestHost } from './guard.ts'

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HarnessConfig {
  const tier = (env.MCP_HARNESS_TIER ?? 'L').toUpperCase()
  if (tier !== 'L' && tier !== 'H') throw new Error(`MCP_HARNESS_TIER must be L or H, got ${tier}`)
  const cfg: HarnessConfig = {
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
  // Refuse before any request leaves the process (src/guard.ts).
  for (const u of [cfg.resourceUrl, cfg.authServerUrl, cfg.scaUrl]) if (u) assertTarget(u, env)
  return cfg
}

export function loadJourneyConfig(
  env: NodeJS.ProcessEnv = process.env,
  readOnly = false,
  verifyRevocation = false,
  verifyProposal = false,
): JourneyConfig {
  const appSignIn = env.MCP_HARNESS_APP_SIGN_IN === 'true'
  const appCounterFile = env.MCP_HARNESS_APP_COUNTER_FILE
  if (!appSignIn && appCounterFile !== undefined) throw new Error('app counter state requires app sign-in')
  const softwareConnect = env.MCP_HARNESS_SOFTWARE_CONNECT_APPROVAL === 'true'
  if (appSignIn && (!softwareConnect || env.MCP_HARNESS_APP_TOKEN !== undefined ||
      !env.MCP_HARNESS_PASSKEY_FILE || !env.MCP_HARNESS_AUTH_SERVER)) {
    throw new Error('app sign-in requires synthetic software approval, an explicit sandbox issuer and passkey file')
  }
  if (appSignIn && !appCounterFile) {
    const issuerHost = new URL(env.MCP_HARNESS_AUTH_SERVER!).hostname
    const loopback = issuerHost === 'localhost' || issuerHost === '[::1]' ||
      /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(issuerHost)
    if (!loopback) throw new Error('shared sandbox app sign-in requires durable passkey counter state')
  }
  if (verifyProposal && (!softwareConnect || readOnly || verifyRevocation ||
      (env.MCP_HARNESS_SCOPE !== undefined && env.MCP_HARNESS_SCOPE !== 'openid'))) {
    throw new Error('proposal verification requires the synthetic software approver and exactly the openid scope')
  }
  if ((verifyRevocation || softwareConnect) &&
      ((!readOnly && !verifyProposal) || env.MCP_HARNESS_SYNTHETIC_SANDBOX !== 'true' ||
      env.MCP_HARNESS_I_UNDERSTAND_NON_LOCAL || env.MCP_HARNESS_SESSION_FILE)) {
    throw new Error('revocation verification and software approval require read-only synthetic sandbox mode or synthetic proposal mode, without a target override or session file')
  }
  if (!readOnly && !env.MCP_HARNESS_PROPOSE_ARGS) {
    throw new Error('MCP_HARNESS_PROPOSE_ARGS must contain the synthetic payment proposal fixture')
  }
  const shared = loadConfig({
    ...env,
    MCP_HARNESS_PROPOSE_ARGS: readOnly ? '{}' : env.MCP_HARNESS_PROPOSE_ARGS,
    MCP_HARNESS_SCOPE: env.MCP_HARNESS_SCOPE ?? (readOnly || verifyProposal ? 'openid' : undefined),
    MCP_HARNESS_RESOURCE_URL: env.MCP_HARNESS_RESOURCE_URL ?? 'https://localhost/customer/mcp',
  })
  const cfg: JourneyConfig = { ...shared, readOnly, appSignIn, appCounterFile }
  const edgeUrl = env.MCP_HARNESS_CUSTOMER_EDGE_URL
  const appToken = env.MCP_HARNESS_APP_TOKEN
  if (softwareConnect) {
    const customerEdge = fixtureEdge(cfg, env)
    const accountId = cfg.readToolArgs?.accountId
    const credentialId = env.MCP_HARNESS_CONNECT_CREDENTIAL_ID
    const keyFile = env.MCP_HARNESS_CONNECT_PRIVATE_KEY_FILE
    if (cfg.readTool !== 'get_balance' || typeof accountId !== 'string' || !FIXTURE_UUID.test(accountId) ||
        !cfg.partyId || !FIXTURE_UUID.test(cfg.partyId) || !credentialId || !/^[A-Za-z0-9_-]{1,128}$/.test(credentialId) || !keyFile) {
      throw new Error('software approval requires the synthetic party, get_balance account, enrolled credential ID and local key file')
    }
    cfg.softwareConnectApproval = { ...customerEdge, partyId: cfg.partyId, accountId, credentialId, keyFile }
  }
  if (verifyRevocation) {
    cfg.revocationVerification = revocationConfig(cfg, env)
    cfg.readToolArgs = { accountId: cfg.revocationVerification.accountId }
    return cfg
  }
  if (readOnly) {
    if (!softwareConnect && (edgeUrl !== undefined || appToken !== undefined)) {
      throw new Error('approval observation is available only for a proposal journey')
    }
    if (!READ_ONLY_TOOLS.has(cfg.readTool)) throw new Error('select a supported read-only MCP tool')
    return cfg
  }
  const required = ['debtorAccountId', 'creditorIban', 'creditorName', 'amount', 'currency', 'clientRequestId']
  if (!cfg.proposeArgs || required.some((field) => {
    const value = cfg.proposeArgs[field]
    return typeof value !== 'string' || !value.trim()
  })) {
    throw new Error(`MCP_HARNESS_PROPOSE_ARGS requires nonempty strings for ${required.join(', ')}`)
  }
  if (verifyProposal) {
    if (cfg.proposeArgs.debtorAccountId !== cfg.softwareConnectApproval?.accountId ||
        (cfg.proposeArgs.currency !== 'CZK' && cfg.proposeArgs.currency !== 'EUR')) {
      throw new Error('proposal verification requires the same selected synthetic debtor account and CZK or EUR currency')
    }
    cfg.verifyProposal = true
    return cfg
  }
  if (edgeUrl !== undefined || appToken !== undefined) {
    if (!edgeUrl || !appToken) throw new Error('approval observation requires a sandbox customer-edge URL and app token')
    for (const url of [edgeUrl, cfg.resourceUrl, cfg.authServerUrl]) if (url) assertScaTarget(url, env)
    const base = new URL(edgeUrl)
    if (base.pathname.replace(/\/$/, '') !== '/customer/v1' || base.search || base.hash) {
      throw new Error('MCP_HARNESS_CUSTOMER_EDGE_URL must end at /customer/v1')
    }
    cfg.approvalObservation = { edgeUrl: base.href.replace(/\/$/, ''), appToken }
    cfg.readTool = 'get_balance'
    cfg.readToolArgs = { accountId: cfg.proposeArgs.debtorAccountId }
  }
  return cfg
}

function fixtureEdge(cfg: JourneyConfig, env: NodeJS.ProcessEnv): { edgeUrl: string; appToken: string } {
  const edgeUrl = env.MCP_HARNESS_CUSTOMER_EDGE_URL
  const appToken = env.MCP_HARNESS_APP_TOKEN ?? ''
  if (!edgeUrl || (!cfg.appSignIn && !appToken.trim())) throw new Error('synthetic customer-edge steps require a sandbox customer-edge URL and app token')
  for (const url of [edgeUrl, cfg.resourceUrl, cfg.authServerUrl, cfg.scaUrl]) if (url) assertScaTarget(url, env)
  const base = new URL(edgeUrl)
  const localHttp = base.hostname === 'localhost' || base.hostname === '[::1]' ||
    (base.hostname.startsWith('127.') && isLocalOrTestHost(base.hostname))
  if (base.pathname.replace(/\/$/, '') !== '/customer/v1' || base.search || base.hash ||
      (base.protocol !== 'https:' && !localHttp)) {
    throw new Error('synthetic customer-edge steps require a sandbox customer-edge URL ending at /customer/v1 (HTTPS or loopback)')
  }
  return { edgeUrl: base.href.replace(/\/$/, ''), appToken }
}

function revocationConfig(cfg: JourneyConfig, env: NodeJS.ProcessEnv): RevocationVerification {
  const customerEdge = fixtureEdge(cfg, env)
  const accountId = cfg.readToolArgs?.accountId
  const currency = env.MCP_HARNESS_VERIFY_BALANCE_CURRENCY
  if (cfg.readTool !== 'get_balance' || typeof accountId !== 'string' || !FIXTURE_UUID.test(accountId) ||
      !currency || !/^[A-Z]{3}$/.test(currency)) {
    throw new Error('revocation verification requires get_balance, a synthetic accountId, and MCP_HARNESS_VERIFY_BALANCE_CURRENCY')
  }
  return { ...customerEdge, accountId, currency }
}
