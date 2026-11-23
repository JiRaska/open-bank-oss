// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import assert from 'node:assert/strict'
import { test } from 'node:test'
import { assertSandboxAppAuthority, redeemSandboxAppAuthorization } from '../src/app-sign-in.ts'
import { loadJourneyConfig } from '../src/config.ts'

const issuer = 'https://auth.test/realms/openbank-customers'
const party = '00000000-0000-4000-8000-000000000001'
const endpoint = `${issuer}/protocol/openid-connect`
const metadata = { issuer, authorization_endpoint: `${endpoint}/auth`, token_endpoint: `${endpoint}/token`,
  authorization_response_iss_parameter_supported: true }
const callback = (overrides: Record<string, string> = {}) => `https://open-bank.tech/app/oauth/callback?${
  new URLSearchParams({ code: 'synthetic-code', state: 'fixture-state', iss: issuer, ...overrides })}`
const redeem = (url = callback()) =>
  redeemSandboxAppAuthorization(metadata, issuer, party, url, 'fixture-state', 'synthetic-verifier')

test('sandbox app sign-in pins the standard customers realm and both exact endpoints', () => {
  assert.doesNotThrow(() => assertSandboxAppAuthority(metadata, issuer))
  assert.throws(() => assertSandboxAppAuthority(metadata, 'https://other.test/realms/openbank-customers'), /exact configured/)
  assert.throws(() => assertSandboxAppAuthority({ ...metadata, token_endpoint: 'https://other.test/realms/openbank-customers/protocol/openid-connect/token' }, issuer), /exact configured/)
  const staff = 'https://auth.test/realms/openbank'
  assert.throws(() => assertSandboxAppAuthority({ ...metadata, issuer: staff }, staff), /exact configured/)
  assert.throws(() => assertSandboxAppAuthority(metadata, 'https://prod.example/realms/openbank-customers'), /never self-approves/)
})

test('app callback must match state, issuer and exact registered URL before token exchange', async (t) => {
  const mocked = t.mock.method(globalThis, 'fetch', async () => { throw new Error('exchange must not start') })
  await assert.rejects(redeem(callback({ state: 'different' })), /state mismatch/)
  await assert.rejects(redeem(callback({ iss: 'https://other.test/realms/openbank-customers' })), /iss mismatch/)
  await assert.rejects(redeem(callback().replace('/callback?', '/callback-other?')), /unexpected app callback/)
  assert.equal(mocked.mock.callCount(), 0)
})

test('app PKCE exchange requests only the app token and verifies its customer claims', async (t) => {
  const claims = {
    iss: issuer, azp: 'openbank-app', aud: ['openbank-edge', 'openbank-rum'], party_id: party, scope: 'openid sca:decide',
    realm_access: { roles: ['ROLE_CUSTOMER'] }, exp: Math.floor(Date.now() / 1000) + 300,
  }
  let tokenClaims: Record<string, unknown> = claims
  const encoded = () => `test.${Buffer.from(JSON.stringify(tokenClaims)).toString('base64url')}.fixture`
  t.mock.method(globalThis, 'fetch', async (url: string, options: RequestInit) => {
    assert.equal(url, metadata.token_endpoint)
    assert.equal(options.method, 'POST')
    assert.equal(options.redirect, 'manual')
    assert.deepEqual(Object.fromEntries(new URLSearchParams(String(options.body))), {
      grant_type: 'authorization_code', code: 'synthetic-code', code_verifier: 'synthetic-verifier',
      redirect_uri: 'https://open-bank.tech/app/oauth/callback', client_id: 'openbank-app',
    })
    return Response.json({ access_token: encoded(), token_type: 'Bearer', refresh_token: 'ignored-by-harness' })
  })
  assert.equal(await redeem(), encoded())
  for (const wrong of [{ aud: 'openbank-rum' }, { azp: 'other' }, { party_id: 'wrong' }, { act: null }, { exp: 1 }]) {
    tokenClaims = { ...claims, ...wrong }
    await assert.rejects(redeem(), /short-lived customer session/)
  }
})

test('durable sign-in is opt-in and requires the existing synthetic approval boundary', () => {
  const env = {
    MCP_HARNESS_APP_SIGN_IN: 'true',
    MCP_HARNESS_SOFTWARE_CONNECT_APPROVAL: 'true',
    MCP_HARNESS_SYNTHETIC_SANDBOX: 'true',
    MCP_HARNESS_AUTH_SERVER: issuer,
    MCP_HARNESS_PASSKEY_FILE: '/synthetic-passkey.json',
    MCP_HARNESS_CUSTOMER_EDGE_URL: 'https://edge.test/customer/v1',
    MCP_HARNESS_PARTY_ID: party,
    MCP_HARNESS_CONNECT_CREDENTIAL_ID: 'synthetic-device',
    MCP_HARNESS_CONNECT_PRIVATE_KEY_FILE: '/synthetic-device.pem',
    MCP_HARNESS_READ_TOOL: 'get_balance',
    MCP_HARNESS_READ_TOOL_ARGS: JSON.stringify({ accountId: party }),
  }
  assert.equal(loadJourneyConfig(env, true).appSignIn, true)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_APP_TOKEN: 'explicit' }, true), /app sign-in requires/)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_AUTH_SERVER: undefined }, true), /app sign-in requires/)
  assert.throws(() => loadJourneyConfig({ ...env, MCP_HARNESS_SYNTHETIC_SANDBOX: '' }, true), /synthetic sandbox/)
  const explicit = loadJourneyConfig({ ...env, MCP_HARNESS_APP_SIGN_IN: undefined, MCP_HARNESS_APP_TOKEN: 'explicit' }, true)
  assert.equal(explicit.softwareConnectApproval?.appToken, 'explicit')
  assert.equal(explicit.appSignIn, false)
})
