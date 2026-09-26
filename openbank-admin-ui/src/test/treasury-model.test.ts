// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { dealSchema, counterpartyListSchema, type Counterparty } from '@/components/treasury/contracts'
import {
  dealActions, eligibleCounterparties, exceedsHeadroom, headroomFor, refusalText, utilisation,
} from '@/components/treasury/model'

const t = (_cs: string, en: string) => en
const DEALER = ['ROLE_TREASURY_DEALER']
const APPROVER = ['ROLE_TREASURY_APPROVER']

const cp = (counterpartyId: string, kind: Counterparty['kind'], currency: string, limit: number, exposure: number): Counterparty => ({
  counterpartyId, name: counterpartyId, kind, synthetic: kind === 'BANK', currency, limit, exposure, headroom: limit - exposure,
})
const ROWS = [cp('CNB', 'CENTRAL_BANK', 'CZK', 1e12, 0), cp('SIM-A', 'BANK', 'CZK', 100, 40), cp('SIM-A', 'BANK', 'EUR', 10, 0)]

describe('dealActions — role x state, with the four-eyes courtesy', () => {
  const pending = { state: 'PENDING_APPROVAL' as const, createdBy: 'dana', submittedBy: 'dana' }

  it('hides approve from the deal’s creator or submitter but keeps reject', () => {
    const own = dealActions(pending, 'dana', APPROVER)
    expect(own.approve).toBe(false)
    expect(own.reject).toBe(true)
    expect(own.ownDeal).toBe(true)
    expect(dealActions({ ...pending, createdBy: 'eva' }, 'dana', APPROVER).approve).toBe(false)
    expect(dealActions(pending, 'petr', APPROVER).approve).toBe(true)
  })

  it('an unreadable identity does not hide approve — the server’s 422 is the control', () => {
    expect(dealActions(pending, null, APPROVER).approve).toBe(true)
  })

  it('follows the lifecycle and the method-level roles', () => {
    expect(dealActions({ ...pending, state: 'DRAFT' }, 'dana', DEALER)).toMatchObject({ submit: true, cancel: true, approve: false })
    expect(dealActions(pending, 'x', DEALER)).toMatchObject({ approve: false, reject: false, cancel: true })
    expect(dealActions({ ...pending, state: 'BOOKED' }, 'x', APPROVER)).toMatchObject({ settle: true, reverse: true, cancel: false })
    expect(dealActions({ ...pending, state: 'SETTLED' }, 'x', APPROVER)).toMatchObject({ mature: true, reverse: true })
    expect(dealActions({ ...pending, state: 'BOOKED' }, 'x', ['ROLE_ADMIN'])).toMatchObject({ settle: false, reverse: false, cancel: false })
  })
})

describe('counterparty helpers', () => {
  it('offers ČNB only for the facility and banks otherwise', () => {
    expect(eligibleCounterparties(ROWS, 'CNB_DEPOSIT_FACILITY').map(c => c.counterpartyId)).toEqual(['CNB'])
    expect(new Set(eligibleCounterparties(ROWS, 'MM_PLACEMENT').map(c => c.counterpartyId))).toEqual(new Set(['SIM-A']))
  })

  it('reads headroom per currency and warns only for asset products', () => {
    const row = headroomFor(ROWS, 'SIM-A', 'CZK')
    expect(row?.headroom).toBe(60)
    expect(exceedsHeadroom('MM_PLACEMENT', 61, row)).toBe(true)
    expect(exceedsHeadroom('MM_PLACEMENT', 60, row)).toBe(false)
    expect(exceedsHeadroom('MM_BORROWING', 1e9, row)).toBe(false)
    expect(utilisation(row!)).toBeCloseTo(0.4)
  })
})

describe('contracts and refusals', () => {
  it('parses BigDecimal as a number or a string', () => {
    expect(counterpartyListSchema.parse([{ ...ROWS[1], limit: '100.00' }])[0].limit).toBe(100)
  })

  it('rejects a deal missing the lifecycle fields instead of half-rendering it', () => {
    expect(dealSchema.safeParse({ dealId: 'x' }).success).toBe(false)
  })

  it('renders four-eyes and limit refusals readably, never as a status line', () => {
    const fe = refusalText({ ok: false, status: 422, kind: 'refused', code: 'FOUR_EYES_VIOLATION', message: 'same person' }, 'Approve', t)
    expect(fe).toContain('four-eyes')
    expect(fe).toContain('same person')
    expect(fe).not.toMatch(/422/)
    expect(refusalText({ ok: false, status: 422, kind: 'refused', code: 'LIMIT_BREACHED', message: null }, 'Approve', t)).toContain('limit breached')
  })
})
