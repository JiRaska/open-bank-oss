// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { loadConfig } from '../src/config.ts'
import { assertScaTarget, assertTarget, guardedFetch, TargetRefused } from '../src/guard.ts'
import { ATTACKS, contextFor } from '../src/attacks.ts'
import { generateDpopKey } from '../src/dpop.ts'
import { authenticatedFetch, connectMcp } from '../src/mcp.ts'
import { discover } from '../src/oauth.ts'
import { SoftwareScaDevice } from '../src/sca-device.ts'
import type { Stub } from './stub-server.ts'
import { startStub } from './stub-server.ts'

const stubs: Stub[] = []
after(async () => { await Promise.all(stubs.map((s) => s.close())) })

const SFX = { MCP_HARNESS_ALLOWED_SUFFIXES: 'example.com, .example.org' }
const ALLOWED = [
  'http://localhost:8150/mcp', 'http://127.0.0.1:1/x', 'http://[::1]:8150/mcp', 'http://mcp.localhost/mcp',
  'https://mcp.bank.test/mcp', 'https://sandbox.example.com/mcp', 'https://staging.mcp.example.org/mcp',
  'https://dev.api.example.com/', 'https://qa.example.org/',
]
const REFUSED = [
  ['production-looking host', 'https://mcp.examplebank.com/mcp'],
  ['userinfo trick', 'https://sandbox.example.com@mcp.examplebank.com/mcp'],
  ['userinfo with password', 'https://localhost:x@mcp.examplebank.com/mcp'],
  ['suffix trick: localhost as a left label', 'https://localhost.examplebank.com/mcp'],
  ['suffix trick: .test not the suffix', 'https://mcp.test.examplebank.com/mcp'],
  ['substring, not a label', 'https://mcp-sandbox.example.com/mcp'],
  ['sandbox label not leftmost', 'https://mcp.sandbox.example.com/mcp'],
  ['leftmost label, suffix not allow-listed', 'https://sandbox.examplebank.com/mcp'],
  ['allow-listed suffix as a substring', 'https://sandbox.evil-example.com/mcp'],
  ['allow-listed suffix not at the end', 'https://sandbox.example.com.evil.net/mcp'],
  ['label is the whole allow-listed domain', 'https://sandbox.com/mcp'],
  ['TLD .dev does not count', 'https://mcp.examplebank.dev/mcp'],
  ['dotted-quad lookalike', 'https://127.0.0.1.nip.io/mcp'],
  ['non-http scheme', 'file:///etc/passwd'],
]

test('allowed targets pass', () => { for (const u of ALLOWED) assert.doesNotThrow(() => assertTarget(u, SFX), u) })

test('refused targets throw, including userinfo, label-position and suffix tricks', () => {
  for (const [why, u] of REFUSED) assert.throws(() => assertTarget(u, SFX), TargetRefused, `${why}: ${u}`)
})

test('with no allow-listed suffix, no labelled host is allowed', () => {
  assert.throws(() => assertTarget('https://sandbox.example.com/mcp', {}), TargetRefused)
})

test('the override must name the exact host', () => {
  assert.doesNotThrow(() => assertTarget('https://mcp.examplebank.com/mcp', { MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'mcp.examplebank.com' }))
  assert.throws(() => assertTarget('https://mcp.examplebank.com/mcp', { MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'examplebank.com' }), TargetRefused)
  assert.throws(() => assertTarget('https://other.examplebank.com/mcp', { MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'mcp.examplebank.com' }), TargetRefused)
  assert.throws(() => assertTarget('https://mcp.examplebank.com@evil.example/mcp', { MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'evil.example' }), TargetRefused, 'userinfo is refused even with an override')
})

test('loadConfig refuses a non-local resource, authorization server or SCA URL', () => {
  assert.throws(() => loadConfig({ MCP_HARNESS_RESOURCE_URL: 'https://mcp.examplebank.com/mcp' }), TargetRefused)
  assert.throws(() => loadConfig({ MCP_HARNESS_AUTH_SERVER: 'https://login.examplebank.com/realms/c' }), TargetRefused)
  assert.throws(() => loadConfig({ MCP_HARNESS_SCA_URL: 'https://sca.examplebank.com' }), TargetRefused)
  assert.doesNotThrow(() => loadConfig({}))
})

test('the SCA device never self-approves against a non-sandbox host — no override exists', () => {
  const env = { MCP_HARNESS_I_UNDERSTAND_NON_LOCAL: 'sca.examplebank.com' }
  assert.doesNotThrow(() => assertTarget('https://sca.examplebank.com', env), 'precondition: the general override accepts it')
  assert.throws(() => assertScaTarget('https://sca.examplebank.com'), TargetRefused)
  assert.throws(() => new SoftwareScaDevice('https://sca.examplebank.com', 'p', 't'), TargetRefused)
  assert.throws(() => new SoftwareScaDevice('https://sandbox.example.com@sca.examplebank.com', 'p', 't'), TargetRefused)
  assert.throws(() => assertScaTarget('https://sca.sandbox.example.com', SFX), TargetRefused, 'label not leftmost')
  assert.doesNotThrow(() => assertScaTarget('https://sandbox.example.com', SFX))
  assert.doesNotThrow(() => new SoftwareScaDevice('http://localhost:8130', 'p', 't'))
})

test('a discovered authorization server is guarded too (metadata cannot redirect the harness)', async () => {
  const stub = await startStub(['prm-foreign-as']); stubs.push(stub)
  await assert.rejects(discover(stub.mcpUrl), TargetRefused)
  const ok = await startStub([]); stubs.push(ok)
  assert.equal((await discover(ok.mcpUrl)).authServer, ok.url)
})

/** A fetch spy: records every request it is asked to make, then delegates to the real fetch. */
function spy() {
  const calls: { url: string; headers: Headers }[] = []
  const f: typeof fetch = async (input, init = {}) => {
    calls.push({ url: input instanceof Request ? input.url : input.toString(), headers: new Headers(init.headers) })
    return fetch(input, init)
  }
  return { f, calls }
}

test('authenticated MCP request: a 307 to a refused host is refused and no credential reaches it', async () => {
  const stub = await startStub(['redirect-mcp-foreign']); stubs.push(stub)
  const dpop = await generateDpopKey()
  const s = spy()
  const f = authenticatedFetch({ accessToken: 'at', scheme: 'DPoP', dpop }, s.f)
  await assert.rejects(f(stub.mcpUrl, { method: 'POST', body: '{}' }), TargetRefused)
  assert.deepEqual(s.calls.map((c) => new URL(c.url).hostname), ['127.0.0.1'], 'only the allowed host was contacted')
  assert.ok(s.calls[0].headers.get('dpop'), 'precondition: the DPoP header was built for the allowed host')
  await assert.rejects(connectMcp(stub.mcpUrl, { accessToken: 'at', scheme: 'DPoP', dpop }), TargetRefused)
})

test('unauthenticated fetches (discovery, SDK transport) re-check every redirect hop', async () => {
  const stub = await startStub(['redirect-prm-foreign']); stubs.push(stub)
  const s = spy()
  await assert.rejects(guardedFetch(s.f)(`${stub.url}/.well-known/oauth-protected-resource/mcp`), TargetRefused)
  assert.deepEqual(s.calls.map((c) => new URL(c.url).hostname), ['127.0.0.1'])
  await assert.rejects(discover(stub.mcpUrl), TargetRefused)
  const m = await startStub(['redirect-mcp-foreign']); stubs.push(m)
  await assert.rejects(connectMcp(m.mcpUrl), TargetRefused)
})

test('the resource_metadata URL named in WWW-Authenticate is guarded before it is fetched', async () => {
  const stub = await startStub(['anon-401-foreign-prm']); stubs.push(stub)
  const c = ATTACKS.find((a) => a.id === 'unauth-401-resource-metadata')!
  await assert.rejects(c.run!(contextFor(loadConfig({ MCP_HARNESS_RESOURCE_URL: stub.mcpUrl }))), TargetRefused)
})
