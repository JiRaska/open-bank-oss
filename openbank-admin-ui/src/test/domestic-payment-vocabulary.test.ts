// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { DOMESTIC_PAYMENT_STATUSES, parsePaymentDetailEvidence } from '@/lib/payments/detailEvidence'

// The operator detail page drops a payment whose status it does not know: the evidence parser
// answers null and the reviewer sees an error instead of the payment. So the list it accepts must
// contain the rail's own enum, read from the Kotlin source: a value added there fails here. The UI
// may know a status before the rail declares it (HELD ships here before the rail's chain does, so
// the two deploy in either order); it may not know one the rail never will.
const MODEL = path.resolve(
  process.cwd(),
  '../openbank-domestic-payment/src/main/kotlin/com/openbank/domestic/domain/model/DomesticPayment.kt',
)

/** The constant names of one Kotlin enum, in declaration order. */
function kotlinEnumNames(source: string, name: string): string[] {
  const body = new RegExp(`enum class ${name}\\s*\\{([\\s\\S]*?)\\n\\}`).exec(source)?.[1]
  if (body === undefined) throw new Error(`enum class ${name} not found in ${MODEL}`)
  return body
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .split(';')[0]
    .split(',')
    .map(entry => entry.trim().replace(/\(.*$/s, ''))
    .filter(entry => entry.length > 0)
}

const ID = '123e4567-e89b-42d3-a456-426614174000'
const payment = {
  id: ID,
  status: 'RECEIVED',
  amount: '1500.00',
  currency: 'CZK',
  debtorAccountNumber: '1234567890',
  debtorBankCode: '0100',
  creditorAccountNumber: '9876543210',
  creditorBankCode: '0800',
  createdAt: '2026-11-13T09:00:00Z',
}

describe('domestic payment vocabulary', () => {
  const source = readFileSync(MODEL, 'utf8')
  const backendStatuses = kotlinEnumNames(source, 'DomesticPaymentStatus')
  const backendReasons = kotlinEnumNames(source, 'DomesticRejectReason')

  it('reads the enums it compares against', () => {
    // A reader that found nothing would make every comparison below pass on an empty list.
    expect(backendStatuses).toContain('RECEIVED')
    expect(backendStatuses).toContain('SETTLED')
    expect(backendStatuses.length).toBeGreaterThanOrEqual(7)
    expect(backendReasons).toContain('INSUFFICIENT_FUNDS')
    expect(backendReasons).toContain('TECHNICAL_ERROR')
    expect(backendReasons.length).toBeGreaterThanOrEqual(9)
    expect(kotlinEnumNames('enum class A {\n    X,\n\n    /** note, with a comma */\n    Y,\n    ;\n\n    val z = 1\n}', 'A')).toEqual(['X', 'Y'])
    expect(() => kotlinEnumNames(source, 'NoSuchEnum')).toThrow(/not found/)
  })

  it("knows every status the rail declares, in the rail's order", () => {
    expect([...DOMESTIC_PAYMENT_STATUSES]).toEqual(expect.arrayContaining(backendStatuses))
    expect([...DOMESTIC_PAYMENT_STATUSES].filter(status => backendStatuses.includes(status))).toEqual(backendStatuses)
    // Ahead of the rail by HELD at most: anything else unknown to the rail is a typo, not a plan.
    for (const extra of DOMESTIC_PAYMENT_STATUSES.filter(status => !backendStatuses.includes(status))) {
      expect(extra).toBe('HELD')
    }
  })

  it('shows the evidence of a held payment', () => {
    expect(parsePaymentDetailEvidence({ ...payment, status: 'HELD' }, ID, 'DOMESTIC')).toMatchObject({
      id: ID,
      type: 'DOMESTIC',
      status: 'HELD',
      amount: 1500,
      debtorAccountNumber: '1234567890',
    })
  })

  it('shows the evidence of a payment in every status the rail declares', () => {
    // Every one, with or without HELD declared yet.
    for (const status of backendStatuses) {
      expect(parsePaymentDetailEvidence({ ...payment, status }, ID, 'DOMESTIC'), status).toMatchObject({ status })
    }
  })

  it('still refuses a status the rail does not declare', () => {
    expect(parsePaymentDetailEvidence({ ...payment, status: 'PARKED' }, ID, 'DOMESTIC')).toBeNull()
    // HELD is the domestic rail's; the SEPA rail has no such status.
    expect(parsePaymentDetailEvidence({ ...payment, status: 'HELD' }, ID, 'SEPA')).toBeNull()
  })

  it('carries every reject reason the rail declares, with its detail', () => {
    for (const rejectReason of backendReasons) {
      expect(
        parsePaymentDetailEvidence({ ...payment, status: 'REJECTED', rejectReason, rejectDetail: 'detail text' }, ID, 'DOMESTIC'),
        rejectReason,
      ).toMatchObject({ status: 'REJECTED', rejectReason, rejectDetail: 'detail text' })
    }
  })
})
