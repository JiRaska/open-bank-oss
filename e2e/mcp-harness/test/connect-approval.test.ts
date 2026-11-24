// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import assert from 'node:assert/strict'
import { createVerify, generateKeyPairSync } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { loadJourneyConfig } from '../src/config.ts'
import { canonicalConnectContext, prepareSyntheticConnectApproval, sha256 } from '../src/connect-approval.ts'

const fixtures = JSON.parse(readFileSync(new URL(
  '../../../openbank-infra/docker/keycloak/extension/src/test/resources/mcp-connect-context-v2.fixture.json',
  import.meta.url,
), 'utf8'))
const first = fixtures.cases[0].input
const base = {
  edgeUrl: 'https://edge.test/customer/v1', appToken: 'synthetic-app-token',
  partyId: first.party_id, accountId: first.accounts[0].id, credentialId: 'synthetic-device',
}
const auth = {
  requestUri: first.request_uri, clientId: first.client_id, redirectUri: first.redirect_uri,
  resource: first.resource, scope: first.scope, parJkt: first.par_jkt, timeoutMs: 10_000,
}
const env = {
  MCP_HARNESS_SOFTWARE_CONNECT_APPROVAL: 'true',
  MCP_HARNESS_SYNTHETIC_SANDBOX: 'true',
  MCP_HARNESS_CUSTOMER_EDGE_URL: base.edgeUrl,
  MCP_HARNESS_APP_TOKEN: base.appToken,
  MCP_HARNESS_PARTY_ID: base.partyId,
  MCP_HARNESS_CONNECT_CREDENTIAL_ID: base.credentialId,
  MCP_HARNESS_CONNECT_PRIVATE_KEY_FILE: '/synthetic/key.pem',
  MCP_HARNESS_READ_TOOL: 'get_balance',
  MCP_HARNESS_READ_TOOL_ARGS: JSON.stringify({ accountId: base.accountId }),
}

test('Connect V2 bytes and SHA-256 match the Keycloak fixtures', () => {
  for (const { name, input, expect } of fixtures.cases.filter(({ expect }) => expect?.canonical)) {
    const canonical = canonicalConnectContext({
      requestRef: sha256(input.request_uri),
      partyId: input.party_id,
      clientId: input.client_id,
      redirectHost: new URL(input.redirect_uri).hostname,
      resource: input.resource,
      scopes: input.scope.split(' '),
      parJkt: input.par_jkt,
      expiresAtEpochSecond: input.expires_at,
      accounts: input.accounts.map(({ id, capabilities }) => ({ accountId: id, capabilities })),
    })
    assert.equal(canonical, expect.canonical, name)
    assert.equal(sha256(canonical), expect.payload_sha256, name)
  }
})

test('software approval requires explicit read-only synthetic mode and a constrained customer-edge', () => {
  assert.equal(loadJourneyConfig(env, true).softwareConnectApproval?.accountId, base.accountId)
  assert.throws(() => loadJourneyConfig(env), /read-only synthetic/)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_SYNTHETIC_SANDBOX: '' }, true), /read-only synthetic/)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'any.example' }, true), /target override/)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_CUSTOMER_EDGE_URL: 'https://any.example/customer/v1' }, true), /never self-approves/)
})

for (const condition of ['matched', 'matched proposal', 'digest mismatch', 'SCA mismatch']) {
  test(`software Connect approval with ${condition}`, async (t) => {
    const dir = mkdtempSync(join(tmpdir(), 'mcp-connect-device-test-'))
    t.after(() => rmSync(dir, { recursive: true }))
    const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const keyFile = join(dir, 'key.pem')
    writeFileSync(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
    const requestRef = sha256(auth.requestUri)
    const proposal = condition === 'matched proposal'
    const review = {
      requestRef, partyId: base.partyId, clientId: auth.clientId, redirectHost: new URL(auth.redirectUri).hostname,
      resource: auth.resource, scopes: auth.scope.split(' '), parJkt: auth.parJkt,
      expiresAtEpochSecond: Math.floor(Date.now() / 1000) + 120,
      accounts: [{ accountId: base.accountId, capabilities: proposal ? ['ACCOUNT_PROPOSE_PAYMENT', 'ACCOUNT_READ_BALANCES'] : ['ACCOUNT_READ_BALANCES'] }],
      challengeId: '02f1f8ad-f472-49fb-a558-5fae34690235', approvalRequestId: `mcp-connect:${requestRef}`,
    }
    const contextSha256 = sha256(canonicalConnectContext(review))
    const reviewPath = `/customer/v1/mcp/connect-requests/${requestRef}`
    const methods: string[] = []
    t.mock.method(globalThis, 'fetch', async (url: string, init: RequestInit) => {
      assert.equal(init.redirect, 'error')
      assert.equal(new Headers(init.headers).get('authorization'), `Bearer ${base.appToken}`)
      const { pathname } = new URL(url)
      methods.push(`${init.method} ${pathname}`)
      if (pathname === reviewPath && methods.length === 1) {
        return Response.json({ ...review, status: 'PENDING_SELECTION', accounts: [] })
      }
      if (pathname === `${reviewPath}/selection`) {
        assert.deepEqual(JSON.parse(init.body as string), { accounts: review.accounts })
        return Response.json({ ...review, status: 'SELECTED' })
      }
      if (pathname === reviewPath) {
        return Response.json({ ...review, status: 'CHALLENGE_BOUND',
          contextSha256: condition === 'digest mismatch' ? 'a'.repeat(64) : contextSha256 })
      }
      if (pathname === '/customer/v1/sca/pending') {
        return Response.json([{ id: review.challengeId, purpose: 'MCP_CLIENT_CONNECT',
          approvalRequestId: review.approvalRequestId, payloadSha256: condition === 'SCA mismatch' ? 'a'.repeat(64) : contextSha256 }])
      }
      assert.equal(pathname, `/customer/v1/sca/challenges/${review.challengeId}/decision`)
      assert.ok(condition === 'matched' || proposal)
      const decision = JSON.parse(init.body as string)
      assert.equal(decision.credentialId, base.credentialId)
      assert.equal(decision.decision, 'APPROVED')
      assert.ok(createVerify('SHA256')
        .update(`${review.challengeId}|APPROVED|MCP_CLIENT_CONNECT|${review.approvalRequestId}|${contextSha256}`)
        .verify(publicKey, decision.signature, 'base64'))
      return Response.json({})
    })
    const driver = prepareSyntheticConnectApproval({ ...base, keyFile }, proposal)
    const initial = [`GET ${reviewPath}`, `POST ${reviewPath}/selection`, `GET ${reviewPath}`]
    if (condition === 'digest mismatch') {
      await assert.rejects(driver(auth), /V2 digest/)
      assert.deepEqual(methods, initial)
    } else if (condition === 'SCA mismatch') {
      await assert.rejects(driver(auth), /verified Connect challenge/)
      assert.deepEqual(methods, [...initial, 'GET /customer/v1/sca/pending'])
    } else {
      await driver(auth)
      assert.deepEqual(methods, [...initial, 'GET /customer/v1/sca/pending',
        `POST /customer/v1/sca/challenges/${review.challengeId}/decision`])
    }
  })
}
