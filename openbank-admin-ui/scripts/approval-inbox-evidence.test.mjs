// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { verifyApprovalInboxEvidence, writeApprovalReadEvidence } from './approval-inbox-evidence.mjs'

const fixture = {
  id: '47fd98d8-a0bb-4a8a-9b16-ea9b18b60447', action: 'billing.synthetic.fixture',
  resourceId: 'synthetic:47fd98d8-a0bb-4a8a-9b16-ea9b18b60447',
  makerId: 'synthetic-approval-e2e:47fd98d8-a0bb-4a8a-9b16-ea9b18b60447',
  createdAt: '2026-09-24T12:00:00.000Z',
}
const expected = () => ({
  sources: { billing: 'ok' },
  items: [{
    id: fixture.id, domain: 'billing', action: fixture.action,
    resourceId: fixture.resourceId, maker: fixture.makerId,
    proposedAt: fixture.createdAt,
  }],
})

test('accepts the exact synthetic billing record', () => {
  assert.deepEqual(verifyApprovalInboxEvidence(expected(), fixture), expected().items[0])
})

for (const [name, mutate] of [
  ['forbidden provider', payload => { payload.sources.billing = 'forbidden' }],
  ['missing row', payload => { payload.items = [] }],
  ['duplicate row', payload => { payload.items.push({ ...payload.items[0] }) }],
  ['wrong action', payload => { payload.items[0].action = 'billing.post' }],
  ['wrong resource', payload => { payload.items[0].resourceId = 'real-fee' }],
  ['wrong maker', payload => { payload.items[0].maker = 'another-operator' }],
  ['wrong timestamp', payload => { payload.items[0].proposedAt = '2026-09-24T12:01:00.000Z' }],
]) {
  test(`rejects ${name}`, () => {
    const payload = expected()
    mutate(payload)
    assert.throws(() => verifyApprovalInboxEvidence(payload, fixture))
  })
}

test('retains only non-secret read evidence in a new owner-only file', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'approval-inbox-evidence-'))
  t.after(() => rm(dir, { recursive: true, force: true }))
  const path = join(dir, 'read.json')
  await writeApprovalReadEvidence(path, 'a'.repeat(40), fixture.id)
  const evidence = JSON.parse(await readFile(path, 'utf8'))
  assert.deepEqual(Object.keys(evidence), ['journey', 'result', 'buildSha', 'fixtureId', 'source', 'assertions'])
  assert.equal(evidence.result, 'read-verified')
  assert.equal(evidence.buildSha, 'a'.repeat(40))
  assert.equal(evidence.fixtureId, fixture.id)
  assert.equal((await stat(path)).mode & 0o777, 0o600)
  await assert.rejects(writeApprovalReadEvidence(path, 'b'.repeat(40), fixture.id), { code: 'EEXIST' })
})

test('refuses to retain approval evidence inside the repository', async () => {
  await assert.rejects(
    writeApprovalReadEvidence(new URL('evidence.json', import.meta.url).pathname, 'a'.repeat(40), fixture.id),
    /outside the repository/,
  )
})
