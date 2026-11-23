// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Issue #16617 layer 2: connect → read → propose, end to end, as a real third-party client would.
// Each step fails with the step name. Proposal mode verifies a pending proposal receipt.
import { chmodSync, writeFileSync } from 'node:fs'
import type { HarnessConfig, JourneyConfig } from './config.ts'
import type { DpopKey } from './dpop.ts'
import { exportDpopKey, generateDpopKey } from './dpop.ts'
import { authorizeInBrowser, openPasskeyBrowser } from './browser.ts'
import { connectMcp } from './mcp.ts'
import type { Credential } from './mcp.ts'
import {
  authorizeUrlFromPar, authorizeUrlFromParams, discover, exchangeCode, pushAuthorizationRequest,
  supportsDpop, supportsPar, validateAuthorizationResponse,
} from './oauth.ts'
import type { AuthorizationParams } from './oauth.ts'
import { pkcePair, randomState } from './pkce.ts'
import type { ScaApprover } from './sca-device.ts'
import { ManualApprover, SoftwareScaDevice } from './sca-device.ts'
import { assertBalanceRead, assertPendingProposal } from './proposal-receipt.ts'
import { observeApproval, verifyPendingOwnerProposal } from './proposal-observation.ts'
import { assertScaTarget } from './guard.ts'
import { prepareSyntheticConnectApproval } from './connect-approval.ts'
import { verifyConnectionRevocation } from './connection-revocation.ts'

/** Only the error text, truncated — never the whole tool payload (it may carry customer data). */
export function toolErrorText(content: unknown): string {
  const texts = Array.isArray(content) ? content.map((c: { text?: unknown }) => (typeof c?.text === 'string' ? c.text : '')).filter(Boolean) : []
  const t = texts.join(' ') || '(no error text)'
  return t.length > 200 ? `${t.slice(0, 200)}…` : t
}

export interface StepResult { step: string; ok: boolean; detail: string }

export type JourneyStep = <T>(name: string, run: () => Promise<T>, describe?: (value: T) => string) => Promise<T>

export interface Session {
  resource: string
  issuer: string
  tokenEndpoint: string
  clientId: string
  accessToken: string
  refreshToken?: string
  scheme: 'DPoP' | 'Bearer'
  dpopPrivateJwk?: JsonWebKey
}

export async function approverFor(cfg: HarnessConfig): Promise<ScaApprover> {
  if (cfg.scaUrl && cfg.scaToken && cfg.partyId) {
    const d = new SoftwareScaDevice(cfg.scaUrl, cfg.partyId, cfg.scaToken)
    await d.enroll()
    return d
  }
  return new ManualApprover()
}

export async function runJourney(cfg: JourneyConfig, log: (r: StepResult) => void = () => {}): Promise<StepResult[]> {
  const results: StepResult[] = []
  const step = async <T>(name: string, fn: () => Promise<T>, describe: (v: T) => string = () => ''): Promise<T> => {
    try {
      const v = await fn()
      const r = { step: name, ok: true, detail: describe(v) }
      results.push(r); log(r)
      return v
    } catch (e) {
      const r = { step: name, ok: false, detail: (e as Error).message }
      results.push(r); log(r)
      throw Object.assign(new Error(`${name}: ${r.detail}`), { results })
    }
  }
  if (!cfg.clientId) throw new Error('MCP_HARNESS_CLIENT_ID is required for the journey')
  const clientId = cfg.clientId

  const synthetic = cfg.softwareConnectApproval && prepareSyntheticConnectApproval(cfg.softwareConnectApproval, cfg.verifyProposal)
  const d = await step('discover (RFC 9728 + RFC 8414)', () => discover(cfg.resourceUrl, cfg.authServerUrl),
    (v) => `issuer=${v.as.issuer} par=${supportsPar(v.as)} dpop=${supportsDpop(v.as)}`)
  if (cfg.approvalObservation || cfg.revocationVerification || synthetic) {
    for (const url of [d.as.issuer, d.as.authorization_endpoint, d.as.token_endpoint, d.as.pushed_authorization_request_endpoint]) {
      if (url) assertScaTarget(url)
    }
  }
  const dpop: DpopKey | undefined = supportsDpop(d.as) ? await generateDpopKey() : undefined
  if (cfg.tier === 'L' && !dpop) throw new Error('Tier L requires DPoP, but the authorization server advertises no ES256 DPoP support')
  if (cfg.revocationVerification && !dpop) throw new Error('revocation verification requires DPoP')
  const pkce = pkcePair()
  const state = randomState()
  const params: AuthorizationParams = {
    clientId, redirectUri: cfg.redirectUri, scope: cfg.scope, state, resource: d.resource,
    codeChallenge: pkce.challenge, codeChallengeMethod: 'S256', dpopJkt: dpop?.jkt,
  }
  const auth = { clientId, clientSecret: cfg.clientSecret }

  if (synthetic && (!dpop || !supportsPar(d.as))) throw new Error('software approval requires PAR and DPoP')
  let requestUri: string | undefined
  const authorizeUrl = await step('authorization request (PAR when advertised)', async () => {
    if (!supportsPar(d.as)) {
      if (d.as.require_pushed_authorization_requests) throw new Error('PAR required but no endpoint advertised')
      return authorizeUrlFromParams(d.as, params)
    }
    const r = await pushAuthorizationRequest(d.as, params, auth, dpop)
    if (r.status !== 201) throw new Error(`PAR HTTP ${r.status} ${r.json?.error ?? ''}`)
    requestUri = r.json.request_uri
    return authorizeUrlFromPar(d.as, clientId, r.json.request_uri)
  })

  const approver = synthetic ? undefined : await approverFor(cfg)
  const browser = await openPasskeyBrowser({ headless: cfg.headless, passkeyFile: cfg.passkeyFile })
  let callback: string
  try {
    callback = await step('passkey login + in-app SCA approval', () => authorizeInBrowser(browser.page, authorizeUrl, cfg.redirectUri, {
      timeoutMs: cfg.timeoutMs,
      whileWaiting: async () => {
        if (synthetic && requestUri && dpop) {
          await synthetic({ requestUri, clientId, redirectUri: cfg.redirectUri, resource: d.resource, scope: cfg.scope,
            parJkt: dpop.jkt, timeoutMs: cfg.timeoutMs })
        } else if (approver) {
          await approver.approve((c) => c.purpose === 'MCP_CLIENT_CONNECT', { timeoutMs: cfg.timeoutMs })
        } else {
          throw new Error('software approval has no correlated PAR and DPoP proof')
        }
      },
    }))
  } finally {
    await browser.close()
  }

  const code = await step('authorization response (state + RFC 9207 iss)', async () =>
    validateAuthorizationResponse(callback, { state, issuer: d.as.issuer, issRequired: Boolean(d.as.authorization_response_iss_parameter_supported) }))

  const tokens = await step('code exchange', async () => {
    const r = await exchangeCode(d.as, { code, verifier: pkce.verifier, redirectUri: cfg.redirectUri, resource: d.resource, auth, dpop })
    if (r.status !== 200) throw new Error(`token HTTP ${r.status} ${r.json?.error ?? ''}`)
    if (dpop && r.json.token_type !== 'DPoP') throw new Error(`expected token_type DPoP, got ${r.json.token_type}`)
    return r.json as { access_token: string; refresh_token?: string; token_type: string }
  }, (t) => `token_type=${t.token_type}`)

  const cred: Credential = { accessToken: tokens.access_token, scheme: dpop ? 'DPoP' : 'Bearer', dpop }
  if (cfg.sessionFile) {
    const s: Session = {
      resource: d.resource, issuer: d.as.issuer, tokenEndpoint: d.as.token_endpoint, clientId,
      accessToken: tokens.access_token, refreshToken: tokens.refresh_token, scheme: cred.scheme,
      dpopPrivateJwk: dpop ? await exportDpopKey(dpop) : undefined,
    }
    writeFileSync(cfg.sessionFile, JSON.stringify(s), { mode: 0o600 })
    chmodSync(cfg.sessionFile, 0o600) // `mode` applies only on creation; an existing file keeps its old mode
  }

  const client = await step('MCP initialize (SDK)', () => connectMcp(cfg.resourceUrl, cred))
  try {
    const proposalId = await exerciseTools(client, cfg, step)
    if (cfg.revocationVerification) {
      if (!dpop) throw new Error('revocation verification requires DPoP')
      await verifyConnectionRevocation(cfg.revocationVerification, {
        client, accessToken: tokens.access_token, refreshToken: tokens.refresh_token, dpop,
        as: d.as, resource: d.resource, auth, timeoutMs: cfg.timeoutMs,
      }, step)
    }
    if (proposalId && cfg.verifyProposal) {
      const app = cfg.softwareConnectApproval
      if (!app) throw new Error('proposal verification requires the synthetic software approver')
      await step('verify the owner sees the same pending proposal', () =>
        verifyPendingOwnerProposal(app, proposalId, cfg.proposeArgs, clientId, cfg.timeoutMs))
    } else if (proposalId && cfg.approvalObservation) {
      const observation = cfg.approvalObservation
      await step('observe in-app SCA approval and payment initiation', () =>
        observeApproval(observation, proposalId, cfg.timeoutMs, () => {
          log({ step: 'app action', ok: true, detail: 'Open the pending proposal in the sandbox app and approve it with SCA' })
        }),
      )
    }
  } finally {
    await client.close()
  }
  return results
}

export async function exerciseTools(
  client: Pick<Awaited<ReturnType<typeof connectMcp>>, 'listTools' | 'callTool'>,
  cfg: JourneyConfig,
  step: JourneyStep,
): Promise<string | undefined> {
  const tools = await step('tools/list', () => client.listTools(), (v) => v.tools.map((t) => t.name).join(','))
  if (!tools.tools.some((t) => t.name === cfg.readTool)) throw new Error(`read tool ${cfg.readTool} not listed`)
  if (cfg.verifyProposal && !tools.tools.some((t) => t.name === 'propose_payment')) {
    throw new Error('propose_payment not listed for the selected V2 account')
  }
  await step(`tools/call ${cfg.readTool}`, async () => {
    const r = await client.callTool({ name: cfg.readTool, arguments: cfg.readToolArgs })
    if (r.isError) throw new Error(`tool error: ${toolErrorText(r.content)}`)
    if (cfg.approvalObservation || cfg.verifyProposal) assertBalanceRead(r.content, cfg.readToolArgs.accountId, cfg.proposeArgs.currency)
    if (cfg.revocationVerification) {
      assertBalanceRead(r.content, cfg.revocationVerification.accountId, cfg.revocationVerification.currency)
    }
  })
  if (cfg.readOnly) return

  return step('tools/call propose_payment (proposal only, never a debit)', async () => {
    const r = await client.callTool({ name: 'propose_payment', arguments: cfg.proposeArgs })
    if (r.isError) throw new Error('propose_payment returned a tool error')
    return assertPendingProposal(r.content, cfg.proposeArgs)
  })
}
