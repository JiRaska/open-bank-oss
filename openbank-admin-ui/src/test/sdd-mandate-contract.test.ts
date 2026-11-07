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

  it('accepts canonical UUID values independently of version bits and omitted nullable dates', () => {
    expect(parseSddMandates([{
      ...valid,
      id: '00000000-0000-0000-0000-000000000000',
      accountId: 'ffffffff-ffff-ffff-ffff-ffffffffffff',
      lastCollectionDate: undefined,
      lastPreNotificationDate: undefined,
    }])).toMatchObject([{
      id: '00000000-0000-0000-0000-000000000000',
      accountId: 'ffffffff-ffff-ffff-ffff-ffffffffffff',
      lastCollectionDate: null,
      lastPreNotificationDate: null,
    }])
  })

  it('preserves opaque creditor identifiers accepted by the service read contract', () => {
    const mandates = [valid,
      { ...valid, id: '33333333-3333-3333-b333-333333333333', umr: 'SYNTHETIC-OPAQUE-1', creditorIdentifier: 'opaque-legacy-id' },
      { ...valid, id: '44444444-4444-4444-a444-444444444444', umr: 'SYNTHETIC-OPAQUE-2', creditorIdentifier: 'legacy/id:2' },
    ]
    expect(parseSddMandates(mandates)).toEqual(mandates)
    expect(() => parseSddMandates([{ ...valid, creditorIdentifier: '' }])).toThrow('Invalid SDD creditorIdentifier')
    expect(() => parseSddMandates([{ ...valid, creditorIdentifier: ' '.repeat(3) }])).toThrow('Invalid SDD creditorIdentifier')
    expect(() => parseSddMandates([{ ...valid, creditorIdentifier: 'x'.repeat(36) }])).toThrow('Invalid SDD creditorIdentifier')
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
