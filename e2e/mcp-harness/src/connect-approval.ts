// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { createHash, createPrivateKey } from 'node:crypto'
import { openSync, readFileSync, fstatSync, closeSync } from 'node:fs'
import { setTimeout } from 'node:timers/promises'
import type { SoftwareConnectApproval } from './config.ts'
import { assertScaTarget } from './guard.ts'
import { linkingPayload, signDevicePayload } from './sca-device.ts'
import type { PendingChallenge } from './sca-device.ts'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const PAR_PREFIX = 'urn:ietf:params:oauth:request_uri:'
const BALANCE = 'ACCOUNT_READ_BALANCES'
const PROPOSE = 'ACCOUNT_PROPOSE_PAYMENT'
const REVIEW_POLL_INTERVAL_MS = 1_800

interface AccountScope { accountId: string; capabilities: string[] }

interface Review {
  requestRef: string
  partyId: string
  clientId: string
  redirectHost: string
  resource: string
  scopes: string[]
  parJkt: string
  expiresAtEpochSecond: number
  status: string
  accounts: AccountScope[]
  challengeId?: string
  approvalRequestId?: string
  contextSha256?: string
}

interface Authorization {
  requestUri: string
  clientId: string
  redirectUri: string
  resource: string
  scope: string
  parJkt: string
  timeoutMs: number
}

const sorted = (values: string[]) => [...new Set(values)].sort()
export const sha256 = (value: string) => createHash('sha256').update(value, 'utf8').digest('hex')

export function canonicalConnectContext(review: Omit<Review, 'status'>): string {
  const accountScopes = review.accounts.map(({ accountId, capabilities }) =>
    `account_scope=${accountId}:${sorted(capabilities).join(',')}`).sort()
  return [
    'openbank-mcp-connect-context/v2',
    `client_id=${review.clientId}`,
    `redirect_host=${review.redirectHost}`,
    `resource=${review.resource}`,
    `scopes=${sorted(review.scopes).join(' ')}`,
    `request_uri_sha256=${review.requestRef}`,
    `expires_at=${review.expiresAtEpochSecond}`,
    `party_id=${review.partyId}`,
    `par_jkt=${review.parJkt}`,
    ...accountScopes,
  ].join('\n')
}

function loadDeviceKey(path: string) {
  const fd = openSync(path, 'r')
  try {
    const metadata = fstatSync(fd)
    if (!metadata.isFile() || (metadata.mode & 0o777) !== 0o600 || metadata.size > 4096) {
      throw new Error('software device key must be a local P-256 PEM file with mode 0600')
    }
    const key = createPrivateKey(readFileSync(fd))
    if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
      throw new Error('software device key must use P-256')
    }
    return key
  } finally {
    closeSync(fd)
  }
}

/** Approve only the fixture account on the exact PAR created by this journey. */
export function prepareSyntheticConnectApproval(cfg: SoftwareConnectApproval, verifyProposal = false) {
  const capabilities = verifyProposal ? [BALANCE, PROPOSE].sort() : [BALANCE]
  assertScaTarget(cfg.edgeUrl)
  const key = loadDeviceKey(cfg.keyFile)
  return async (auth: Authorization): Promise<void> => {
    const parId = auth.requestUri.startsWith(PAR_PREFIX) ? auth.requestUri.slice(PAR_PREFIX.length) : ''
    if (!/^[A-Za-z0-9_-]+$/.test(parId)) throw new Error('software approval requires a canonical PAR request URI')
    const requestRef = sha256(auth.requestUri)
    const reviewPath = `/mcp/connect-requests/${requestRef}`
    const signal = AbortSignal.timeout(auth.timeoutMs)
    const headers = { authorization: `Bearer ${cfg.appToken}`, 'content-type': 'application/json' }
    const edge = (path: string, body?: unknown) => fetch(`${cfg.edgeUrl}${path}`, {
      method: body === undefined ? 'GET' : 'POST', headers, redirect: 'error', signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const accounts = [{ accountId: cfg.accountId, capabilities }]
    const expectedScopes = sorted(auth.scope.split(' ').filter(Boolean))
    let submitted = false
    while (!signal.aborted) {
      const response = await edge(reviewPath)
      if (response.status === 404) {
        await setTimeout(REVIEW_POLL_INTERVAL_MS, undefined, { signal })
        continue
      }
      if (!response.ok) throw new Error(`customer-edge Connect review HTTP ${response.status}`)
      const review = await response.json() as Review
      if (review.requestRef !== requestRef || review.partyId !== cfg.partyId || review.clientId !== auth.clientId ||
          review.redirectHost !== new URL(auth.redirectUri).hostname || review.resource !== auth.resource ||
          review.parJkt !== auth.parJkt || !Array.isArray(review.scopes) ||
          JSON.stringify(sorted(review.scopes)) !== JSON.stringify(expectedScopes) ||
          !Number.isSafeInteger(review.expiresAtEpochSecond) || review.expiresAtEpochSecond <= Date.now() / 1000) {
        throw new Error('customer-edge Connect review does not match the synthetic authorization')
      }
      if (review.status === 'PENDING_SELECTION' && !submitted) {
        const selected = await edge(`${reviewPath}/selection`, { accounts })
        if (!selected.ok) throw new Error(`customer-edge Connect selection HTTP ${selected.status}`)
        submitted = true
      } else if (review.status === 'CHALLENGE_BOUND') {
        if (!submitted || review.accounts?.length !== 1 || review.accounts[0]?.accountId !== cfg.accountId ||
            !Array.isArray(review.accounts[0]?.capabilities) ||
            review.accounts[0].capabilities.length !== capabilities.length ||
            JSON.stringify(sorted(review.accounts[0].capabilities)) !== JSON.stringify(capabilities) ||
            !review.challengeId || !UUID.test(review.challengeId) || review.approvalRequestId !== `mcp-connect:${requestRef}` ||
            review.contextSha256 !== sha256(canonicalConnectContext(review))) {
          throw new Error('customer-edge Connect challenge does not match the synthetic selection and V2 digest')
        }
        const pending = await edge('/sca/pending')
        if (pending.status !== 200) throw new Error(`customer-edge pending SCA HTTP ${pending.status}`)
        const matches = (await pending.json() as PendingChallenge[]).filter((challenge) =>
          challenge.id === review.challengeId && challenge.purpose === 'MCP_CLIENT_CONNECT' &&
          challenge.approvalRequestId === review.approvalRequestId && challenge.payloadSha256 === review.contextSha256)
        if (matches.length !== 1) throw new Error('pending SCA does not contain the verified Connect challenge')
        const [challenge] = matches
        const decision = await edge(`/sca/challenges/${challenge.id}/decision`, {
          credentialId: cfg.credentialId, decision: 'APPROVED',
          signature: signDevicePayload(key, linkingPayload(challenge, 'APPROVED')),
        })
        if (decision.status !== 200) throw new Error(`customer-edge Connect decision HTTP ${decision.status}`)
        return
      } else if (review.status !== 'SELECTED' && review.status !== 'PENDING_SELECTION') {
        throw new Error('customer-edge Connect request is no longer pending')
      }
      await setTimeout(REVIEW_POLL_INTERVAL_MS, undefined, { signal })
    }
    throw new Error('customer-edge Connect approval timed out')
  }
}
