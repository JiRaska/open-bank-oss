// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseSddMandates } from '@/lib/sdd/sddMandateContract'

const valid = {
  id: '11111111-1111-4111-8111-111111111111',
  accountId: '22222222-2222-4222-a222-222222222222',
  debtorIban: 'CZ6508000000192000145399',
  creditorIdentifier: 'CZ98ZZZ00000000001',
  umr: 'UMR-EVIDENCE-42',
  scheme: 'B2B',
  sequenceType: 'RCUR',
  creditorName: 'Verified Utilities SE',
  debtorName: 'Example Manufacturing a.s.',
  signatureDate: '2026-01-15',
  status: 'ACTIVE',
  b2bConfirmed: true,
  lastCollectionDate: '2026-08-31',
  lastPreNotificationDate: '2026-08-20',
  createdAt: '2026-01-15T08:00:00Z',
  amendments: [],
}

describe('SDD mandate response contract', () => {
  it('preserves the complete authoritative mandate evidence', () => {
    expect(parseSddMandates([valid])).toEqual([valid])
  })

  it('rejects partial records and invented enum values', () => {
    expect(() => parseSddMandates([{ ...valid, sequenceType: 'MONTHLY' }])).toThrow('Invalid SDD sequenceType')
    expect(() => parseSddMandates([{ ...valid, b2bConfirmed: undefined }])).toThrow('Invalid SDD b2bConfirmed')
    expect(() => parseSddMandates({ mandates: [valid] })).toThrow('Invalid SDD mandate list')
  })

  it('rejects ambiguous identity, routing, time and lifecycle evidence', () => {
    expect(() => parseSddMandates([{ ...valid, id: 'mandate-42' }])).toThrow('Invalid SDD identity')
    expect(() => parseSddMandates([{ ...valid, debtorIban: 'CZ0000000000000000000000' }])).toThrow('Invalid SDD debtorIban')
    expect(() => parseSddMandates([{ ...valid, createdAt: '15/01/2026 08:00' }])).toThrow('Invalid SDD createdAt')
    expect(() => parseSddMandates([{ ...valid, scheme: 'CORE', b2bConfirmed: true }])).toThrow('Invalid SDD B2B confirmation')
    expect(() => parseSddMandates([{ ...valid, scheme: 'CORE', status: 'PENDING_CONFIRMATION', b2bConfirmed: false }])).toThrow('Invalid SDD pending confirmation')
  })

  it('rejects duplicate technical and rulebook identities', () => {
    expect(() => parseSddMandates([valid, valid])).toThrow('Duplicate SDD mandate id')
    expect(() => parseSddMandates([valid, { ...valid, id: '33333333-3333-3333-b333-333333333333' }]))
      .toThrow('Duplicate SDD mandate reference')
  })
})
