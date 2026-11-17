// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Known-positive proof for the attack suite: every case with a body passes against a correct
// stub and FAILS against a stub with exactly the defence it targets switched off. A case that
// passes against its broken twin is decoration, and this test turns red.
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, describe, test } from 'node:test'
import { ATTACKS, contextFor, exitCodeFor, forgedJwt, runAttacks } from '../src/attacks.ts'
import type { AttackContext } from '../src/attacks.ts'
import { listReport } from '../src/cli.ts'
import { loadConfig } from '../src/config.ts'
import { exportDpopKey, generateDpopKey } from '../src/dpop.ts'
import type { Flaw, Stub } from './stub-server.ts'
import { startStub } from './stub-server.ts'

const stubs: Stub[] = []
after(async () => { await Promise.all(stubs.map((s) => s.close())) })

const appToken = forgedJwt({ azp: 'openbank-app', aud: 'account', iss: 'https://kc.invalid/realms/customers' })

async function ctxFor(flaws: Flaw[]): Promise<AttackContext> {
  const stub = await startStub(flaws)
  stubs.push(stub)
  const dpop = await generateDpopKey()
  const token = `bound-${Math.random()}`
  stub.trust(token, dpop.jkt)
  const dir = mkdtempSync(join(tmpdir(), 'mcp-harness-'))
  const sessionFile = join(dir, 'kp.session.json')
  writeFileSync(sessionFile, JSON.stringify({ resource: stub.mcpUrl, issuer: stub.url, tokenEndpoint: `${stub.url}/token`, clientId: 'c1', accessToken: token, scheme: 'DPoP', dpopPrivateJwk: await exportDpopKey(dpop) }), { mode: 0o600 })
  return contextFor(loadConfig({
    MCP_HARNESS_RESOURCE_URL: stub.mcpUrl, MCP_HARNESS_CLIENT_ID: 'c1', MCP_HARNESS_OTHER_CLIENT_ID: 'c2',
    MCP_HARNESS_FOREIGN_TOKEN: appToken, MCP_HARNESS_SESSION_FILE: sessionFile,
  }))
}

const byId = (id: string) => { const c = ATTACKS.find((a) => a.id === id); assert.ok(c?.run, `${id} has a body`); return c! }

/** case id → the single flaw that must turn it red. */
const KNOWN_POSITIVE: Record<string, Flaw> = {
  'unauth-tools-list-empty': 'leak-tools-list',
  'unauth-read-tool-no-data': 'leak-read-data',
  'unauth-propose-payment-refused': 'leak-propose',
  'forged-token-401': 'accept-any-token',
  'openbank-app-token-401': 'accept-any-token',
  'bearer-on-jkt-grant-401': 'dpop-accept-bearer',
  'dpop-jti-replay-401': 'dpop-no-replay-store',
  'dpop-htu-mismatch-401': 'dpop-ignore-htu',
  'dpop-proof-other-key-401': 'dpop-ignore-key',
  'pkce-plain-rejected': 'as-accept-plain-pkce',
  'pkce-absent-rejected': 'as-accept-missing-pkce',
  'request-uri-reuse-rejected': 'as-reusable-request-uri',
  'resource-confusion-invalid-target': 'as-accept-any-resource',
}

describe('known-positive: each case passes on a correct server and fails on its broken twin', () => {
  for (const [id, flaw] of Object.entries(KNOWN_POSITIVE)) {
    test(`${id} ⟂ ${flaw}`, async () => {
      const c = byId(id)
      await c.run!(await ctxFor([]))
      await assert.rejects(c.run!(await ctxFor([flaw])), (e: Error) => e.constructor.name !== 'Skip', `${id} did not detect ${flaw}`)
    })
  }

  test('every ACTIVE case has a known-positive', () => {
    for (const c of ATTACKS.filter((a) => a.status === 'ACTIVE')) assert.ok(KNOWN_POSITIVE[c.id], `${c.id} has no broken-twin proof`)
  })
})

describe('status discipline', () => {
  test('every PENDING case names what enables it; bodiless ones say what they need', () => {
    for (const c of ATTACKS.filter((a) => a.status === 'PENDING')) {
      assert.match(c.enabledBy ?? '', /#\d+/, `${c.id} enabledBy must cite an issue`)
      if (!c.run) assert.ok(c.needs, `${c.id} has neither a body nor a stated need`)
    }
  })

  test('ids are unique and every case states an expected outcome', () => {
    assert.equal(new Set(ATTACKS.map((a) => a.id)).size, ATTACKS.length)
    for (const c of ATTACKS) assert.ok(c.expected.length > 5, c.id)
  })

  test('PENDING is never reported as PASS, even when its body would pass', async () => {
    const ctx = await ctxFor([])
    const def = await runAttacks(ATTACKS, ctx, { includePending: false })
    assert.ok(def.filter((r) => r.status === 'PENDING').every((r) => r.outcome === 'PENDING'))
    const inc = await runAttacks([byId('dpop-jti-replay-401')], await ctxFor([]), { includePending: true })
    assert.equal(inc[0].outcome, 'PENDING-WOULD-PASS')
    assert.equal(exitCodeFor(inc), 0)
  })

  test('an ACTIVE case missing its precondition is SKIP and the run exits non-zero', async () => {
    const stub = await startStub([]); stubs.push(stub)
    const r = await runAttacks([byId('openbank-app-token-401')], contextFor(loadConfig({ MCP_HARNESS_RESOURCE_URL: stub.mcpUrl })), { includePending: false })
    assert.equal(r[0].outcome, 'SKIP')
    assert.equal(exitCodeFor(r), 3)
  })

  test('a token that is not an openbank-app token is refused as a precondition, not used', async () => {
    const stub = await startStub([]); stubs.push(stub)
    const ctx = contextFor(loadConfig({ MCP_HARNESS_RESOURCE_URL: stub.mcpUrl, MCP_HARNESS_FOREIGN_TOKEN: forgedJwt({ azp: 'something-else' }) }))
    const r = await runAttacks([byId('openbank-app-token-401')], ctx, { includePending: false })
    assert.equal(r[0].outcome, 'SKIP')
  })

  test('--list report counts ACTIVE and PENDING', () => {
    const head = listReport(ATTACKS).split('\n')[0]
    const active = ATTACKS.filter((a) => a.status === 'ACTIVE').length
    assert.match(head, new RegExp(`^ACTIVE ${active}  PENDING ${ATTACKS.length - active} `))
  })
})
