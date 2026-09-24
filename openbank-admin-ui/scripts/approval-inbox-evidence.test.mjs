// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict'
import test from 'node:test'
import { verifyApprovalInboxEvidence } from './approval-inbox-evidence.mjs'

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
  assert.doesNotThrow(() => verifyApprovalInboxEvidence(expected(), fixture))
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
