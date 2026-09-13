// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseSwiftMessages, swiftLifecycleGroup, swiftStatusTone } from '@/lib/swift/swiftMessageContract'

const valid = {
  id: '8ab7a9bb-cffc-4fd9-8792-ce84eb0bd379',
  messageType: 'MT103',
  senderBic: 'KOMBCZPPXXX',
  receiverBic: 'DEUTDEFFXXX',
  amount: 85000.25,
  currency: 'EUR',
  status: 'VALIDATED',
  createdAt: '2026-09-09T12:00:00Z',
  reference: 'SWIFT-REF-0042',
}

describe('SWIFT message response contract', () => {
  it('accepts the service response and its compatibility envelope', () => {
    expect(parseSwiftMessages([valid])).toEqual([valid])
    expect(parseSwiftMessages({ messages: [valid] })).toEqual([valid])
  })

  it('rejects invented lifecycle states and malformed evidence', () => {
    expect(() => parseSwiftMessages([{ ...valid, status: 'PROCESSING' }])).toThrow('Invalid SWIFT status')
    expect(() => parseSwiftMessages([{ ...valid, amount: Number.NaN }])).toThrow('Invalid SWIFT amount')
    expect(() => parseSwiftMessages({ items: [valid] })).toThrow('Invalid SWIFT message list')
  })

  it('rejects ambiguous identity, routing and time evidence', () => {
    expect(() => parseSwiftMessages([{ ...valid, id: 'swift-42' }])).toThrow('Invalid SWIFT id')
    expect(() => parseSwiftMessages([{ ...valid, senderBic: 'NOT-A-BIC' }])).toThrow('Invalid SWIFT BIC')
    expect(() => parseSwiftMessages([{ ...valid, createdAt: '09/09/2026 12:00' }])).toThrow('Invalid SWIFT createdAt')
    expect(() => parseSwiftMessages([valid, valid])).toThrow('Duplicate SWIFT message id')
  })

  it('classifies every service lifecycle state without a success fall-through', () => {
    expect((['PENDING', 'VALIDATED', 'SENT'] as const).map(swiftLifecycleGroup)).toEqual(['in_flight', 'in_flight', 'in_flight'])
    expect((['ACKNOWLEDGED', 'COMPLETED'] as const).map(swiftLifecycleGroup)).toEqual(['confirmed', 'confirmed'])
    expect((['REJECTED', 'FAILED'] as const).map(swiftLifecycleGroup)).toEqual(['exception', 'exception'])
    expect((['PENDING', 'VALIDATED', 'SENT', 'ACKNOWLEDGED', 'COMPLETED', 'REJECTED', 'FAILED'] as const).map(swiftStatusTone))
      .toEqual(['warning', 'info', 'info', 'success', 'success', 'danger', 'danger'])
  })
})
