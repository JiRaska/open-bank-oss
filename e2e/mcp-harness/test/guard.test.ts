// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { loadConfig } from '../src/config.ts'
import { assertScaTarget, assertTarget, TargetRefused } from '../src/guard.ts'
import { discover } from '../src/oauth.ts'
import { SoftwareScaDevice } from '../src/sca-device.ts'
import type { Stub } from './stub-server.ts'
import { startStub } from './stub-server.ts'

const stubs: Stub[] = []
after(async () => { await Promise.all(stubs.map((s) => s.close())) })

const ALLOWED = [
  'http://localhost:8150/mcp', 'http://127.0.0.1:1/x', 'http://[::1]:8150/mcp', 'http://mcp.localhost/mcp',
  'https://mcp.bank.test/mcp', 'https://mcp.sandbox.example.com/mcp', 'https://staging.example.org/mcp',
  'https://api.dev.example.net/', 'https://qa.example.com/',
]
const REFUSED = [
  ['production-looking host', 'https://mcp.examplebank.com/mcp'],
  ['userinfo trick', 'https://sandbox.example.com@mcp.examplebank.com/mcp'],
  ['userinfo with password', 'https://localhost:x@mcp.examplebank.com/mcp'],
  ['suffix trick: localhost as a left label', 'https://localhost.examplebank.com/mcp'],
  ['suffix trick: .test not the suffix', 'https://mcp.test.examplebank.com/mcp'],
  ['substring, not a label', 'https://mcp-sandbox.examplebank.com/mcp'],
  ['TLD .dev does not count', 'https://mcp.examplebank.dev/mcp'],
  ['dotted-quad lookalike', 'https://127.0.0.1.nip.io/mcp'],
  ['non-http scheme', 'file:///etc/passwd'],
]

test('allowed targets pass', () => { for (const u of ALLOWED) assert.doesNotThrow(() => assertTarget(u, {}), u) })

test('refused targets throw, including userinfo and suffix tricks', () => {
  for (const [why, u] of REFUSED) assert.throws(() => assertTarget(u, {}), TargetRefused, `${why}: ${u}`)
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
  assert.doesNotThrow(() => new SoftwareScaDevice('http://localhost:8130', 'p', 't'))
})

test('a discovered authorization server is guarded too (metadata cannot redirect the harness)', async () => {
  const stub = await startStub(['prm-foreign-as']); stubs.push(stub)
  await assert.rejects(discover(stub.mcpUrl), TargetRefused)
  const ok = await startStub([]); stubs.push(ok)
  assert.equal((await discover(ok.mcpUrl)).authServer, ok.url)
})
