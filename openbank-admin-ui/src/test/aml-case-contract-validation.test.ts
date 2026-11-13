// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The AML register is fail-fast: a malformed row rejects the whole response with a message that
// names the field. Each assertion pins the EXACT message, so a weakened check that happens to
// throw later, on a different field, is caught rather than read as the same refusal.

import { describe, expect, it } from 'vitest'
import { parseAmlCase } from '@/lib/aml/amlCaseContract'

const base = {
  id: '11111111-1111-4111-8111-111111111111', partyId: '22222222-2222-4222-8222-222222222222',
  accountId: null, transactionId: null, customerReference: 'onboarding-1', screeningType: 'CUSTOMER_ONBOARDING',
  riskLevel: 'LOW', status: 'OPEN', alertCode: 'X', alertDetail: null, matchedEntity: null, decisionReason: null,
  assignedAnalyst: null, decidedBy: null, screenedAt: '2026-11-01T00:00:00Z', decidedAt: null,
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
}
const fails = (row: unknown) => {
  try { parseAmlCase(row); return 'accepted' } catch (e) { return (e as Error).message }
}

describe('AML case row validation', () => {
  it.each([[null], ['row'], [42], [[base]]])('refuses a non-object row %#', row => {
    expect(fails(row)).toBe('Invalid AML case')
  })

  it.each(['id', 'partyId', 'customerReference', 'alertCode'])('names a missing, blank or non-string %s', field => {
    expect(fails({ ...base, [field]: undefined })).toBe(`Invalid AML case ${field}`)
    expect(fails({ ...base, [field]: '' })).toBe(`Invalid AML case ${field}`)
    expect(fails({ ...base, [field]: '   ' })).toBe(`Invalid AML case ${field}`)
    expect(fails({ ...base, [field]: 7 })).toBe(`Invalid AML case ${field}`)
  })

  it.each([['screeningType', 'ONBOARDING'], ['riskLevel', 'SEVERE'], ['status', 'DONE']])('names an unknown %s', (field, value) => {
    expect(fails({ ...base, [field]: value })).toBe(`Invalid AML case ${field}`)
    expect(fails({ ...base, [field]: 1 })).toBe(`Invalid AML case ${field}`)
  })

  it.each(['accountId', 'transactionId', 'alertDetail', 'matchedEntity', 'decisionReason', 'assignedAnalyst', 'decidedBy'])(
    'keeps optional %s null when absent and refuses it blank', field => {
      expect((parseAmlCase({ ...base, [field]: undefined }) as Record<string, unknown>)[field]).toBeNull()
      expect((parseAmlCase({ ...base, [field]: 'v' }) as Record<string, unknown>)[field]).toBe('v')
      expect(fails({ ...base, [field]: ' ' })).toBe(`Invalid AML case ${field}`)
    })

  it.each(['screenedAt', 'createdAt', 'updatedAt'])('names a missing or unparseable %s', field => {
    expect(fails({ ...base, [field]: 'not a date' })).toBe(`Invalid AML case ${field}`)
    expect(fails({ ...base, [field]: undefined })).toBe(`Invalid AML case ${field}`)
  })

  it('keeps every field of a valid row verbatim', () => {
    const full = { ...base, accountId: 'a', transactionId: 't', alertDetail: 'd', matchedEntity: 'm', decisionReason: 'r',
      assignedAnalyst: 'alice', decidedBy: 'bob', decidedAt: '2026-11-02T00:00:00Z', requiresFourEyesDisposition: true }
    expect(parseAmlCase(full)).toEqual(full)
    expect(parseAmlCase({ ...base, requiresFourEyesDisposition: 'true' }).requiresFourEyesDisposition).toBe(false)
  })
})
