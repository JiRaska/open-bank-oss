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
import { assertionLog, ATTACKS, contextFor, exitCodeFor, forgedJwt, runAttacks, Skip } from '../src/attacks.ts'
import { DELIVERY_CHECKS } from '../src/delivery-checks.ts'
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

/**
 * case id → the single flaw that must turn it red, the failure message that flaw must produce
 * (pinned: a twin failing for an unrelated reason does not count), and the label of the case's
 * final assertion, which the run against the correct stub must reach.
 */
const R401 = (what: string) => new RegExp(`^${what}: expected HTTP 401 \\(got HTTP 200\\)$`)
const REFUSED = /^expected the authorization request to be refused \(got HTTP 200\)$/
const KNOWN_POSITIVE: Record<string, { flaw: Flaw; error: RegExp; final: string }> = {
  'unauth-tools-list-empty': { flaw: 'leak-tools-list', error: /^anonymous caller was shown 2 tools: list_accounts,propose_payment$/, final: 'tools/list empty' },
  'unauth-read-tool-no-data': { flaw: 'leak-read-data', error: /^expected isError=true/, final: 'no-data' },
  'unauth-propose-payment-refused': { flaw: 'leak-propose', error: /^expected isError=true/, final: 'no-data' },
  'forged-token-401': { flaw: 'accept-any-token', error: R401('forged token'), final: 'forged token' },
  'openbank-app-token-401': { flaw: 'accept-any-token', error: R401('openbank-app token, tools/list'), final: 'openbank-app token, tools/call' },
  'bearer-on-jkt-grant-401': { flaw: 'dpop-accept-bearer', error: R401('bound token as Bearer'), final: 'bound token as Bearer' },
  'dpop-proof-missing-401': { flaw: 'dpop-proof-optional', error: R401('missing proof'), final: 'missing proof' },
  'dpop-proof-other-key-401': { flaw: 'dpop-ignore-key', error: R401('proof for another key'), final: 'proof for another key' },
  'dpop-bad-signature-401': { flaw: 'dpop-ignore-signature', error: R401('bad signature'), final: 'bad signature' },
  'dpop-jti-replay-401': { flaw: 'dpop-no-replay-store', error: R401('replayed proof'), final: 'replayed proof' },
  'dpop-htu-mismatch-401': { flaw: 'dpop-ignore-htu', error: R401('htu mismatch'), final: 'htu mismatch' },
  'dpop-htm-mismatch-401': { flaw: 'dpop-ignore-htm', error: R401('htm mismatch'), final: 'htm mismatch' },
  'dpop-stale-iat-401': { flaw: 'dpop-ignore-iat', error: R401('stale proof'), final: 'stale proof' },
  'dpop-ath-mismatch-401': { flaw: 'dpop-ignore-ath', error: R401('ath mismatch'), final: 'ath mismatch' },
  'pkce-plain-rejected': { flaw: 'as-accept-plain-pkce', error: /^expected HTTP 400 error=invalid_request \(got HTTP 201\)$/, final: 'oauth-error invalid_request' },
  'pkce-absent-rejected': { flaw: 'as-accept-missing-pkce', error: /^expected HTTP 400 error=invalid_request \(got HTTP 201\)$/, final: 'oauth-error invalid_request' },
  'resource-confusion-invalid-target': { flaw: 'as-accept-any-resource', error: /^expected HTTP 400 error=invalid_target \(got HTTP 201\)$/, final: 'oauth-error invalid_target' },
  'par-required': { flaw: 'as-allow-front-channel', error: REFUSED, final: 'authorize refused' },
  'request-uri-reuse-rejected': { flaw: 'as-reusable-request-uri', error: REFUSED, final: 'authorize refused' },
  'request-uri-other-client-rejected': { flaw: 'as-ignore-client-binding', error: REFUSED, final: 'authorize refused' },
  'request-uri-differing-params-rejected': { flaw: 'as-ignore-extra-params', error: REFUSED, final: 'authorize refused' },
  'iss-in-authorization-response': { flaw: 'as-no-iss-support', error: /^authorization_response_iss_parameter_supported is not true$/, final: 'iss enforced' },
}

describe('known-positive: each case passes on a correct server and fails on its broken twin', () => {
  for (const [id, kp] of Object.entries(KNOWN_POSITIVE)) {
    test(`${id} ⟂ ${kp.flaw}`, async () => {
      const c = byId(id)
      assertionLog.length = 0
      await c.run!(await ctxFor([]))
      assert.equal(assertionLog.at(-1), kp.final, `${id}: the correct-stub run did not reach its final assertion (log: ${assertionLog.join(' | ')})`)
      await assert.rejects(c.run!(await ctxFor([kp.flaw])), (e: Error) => {
        assert.ok(!(e instanceof Skip), `${id} skipped instead of failing`)
        assert.match(e.message, kp.error, `${id} failed for an unrelated reason`)
        return true
      })
    })
  }

  test('every ACTIVE case has a known-positive', () => {
    for (const c of ATTACKS.filter((a) => a.status === 'ACTIVE')) assert.ok(KNOWN_POSITIVE[c.id], `${c.id} has no broken-twin proof`)
  })
})

describe('delivery-check traceability', () => {
  test('every case is mapped to an ADR delivery-check line, and every mapped id exists', () => {
    const mapped = new Set(DELIVERY_CHECKS.flatMap((d) => d.cases ?? []))
    for (const c of ATTACKS) assert.ok(mapped.has(c.id), `${c.id} traces to no delivery-check line`)
    for (const id of mapped) assert.ok(ATTACKS.some((c) => c.id === id), `delivery-check table names unknown case ${id}`)
    for (const d of DELIVERY_CHECKS) assert.ok(d.cases?.length || d.layer, `${d.line} is mapped to nothing`)
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
