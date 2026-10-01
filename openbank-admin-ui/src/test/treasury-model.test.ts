// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { dealSchema, counterpartyListSchema, type Counterparty } from '@/components/treasury/contracts'
import {
  dealActions, eligibleCounterparties, exceedsHeadroom, fxCounterAmount, fxForeignCurrency,
  fxSpotDate, headroomFor, isValidFxPair, productLabel, refusalText, utilisation,
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

  it('offers confirm on a BOOKED deal to a back-office approver who neither created nor submitted it (ADR-0315 D2)', () => {
    const booked = { ...pending, state: 'BOOKED' as const }
    expect(dealActions(booked, 'petr', APPROVER).confirm).toBe(true)
    expect(dealActions(booked, 'dana', APPROVER).confirm).toBe(false)
    expect(dealActions(booked, 'petr', DEALER).confirm).toBe(false)
    expect(dealActions({ ...booked, state: 'CONFIRMED' }, 'petr', APPROVER)).toMatchObject({ confirm: false, settle: true, reverse: true })
    expect(dealActions({ ...booked, state: 'SETTLED' }, 'petr', APPROVER).confirm).toBe(false)
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

  it('parses a CONFIRMED deal instead of treating the whole response as unavailable (ADR-0315 D2)', () => {
    const confirmed = {
      dealId: 'x', product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000, rate: 4,
      dayCount: 'ACT/360', days: 1, interest: 0, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
      state: 'CONFIRMED', createdBy: 'dana', createdByType: 'HUMAN', submittedBy: 'dana', approvedBy: 'adam', rationale: null,
      limitCheck: null, createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:00:00Z', history: [], journals: [],
    }
    expect(dealSchema.safeParse(confirmed).success).toBe(true)
  })

  it('does not reject an unknown future product (ProductType is x-extensible, #10896)', () => {
    const base = {
      dealId: 'x', product: 'FX_FORWARD', counterpartyId: 'SIM-A', currency: 'EUR', principal: 1000, rate: 25,
      dayCount: 'ACT/360', days: 1, interest: 0, tradeDate: '2026-09-25', valueDate: '2026-09-29', maturityDate: '2026-09-29',
      state: 'DRAFT', createdBy: 'dana', createdByType: 'HUMAN', submittedBy: null, approvedBy: null, rationale: null,
      limitCheck: null, createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:00:00Z', history: [], journals: [],
    }
    const parsed = dealSchema.safeParse(base)
    expect(parsed.success).toBe(true)
    if (parsed.success) {
      expect(parsed.data.product).toBe('FX_FORWARD')
      expect(parsed.data.fx).toBeNull()
      // Rendering falls back to the raw string instead of throwing or rendering blank.
      expect(productLabel(parsed.data.product, t)).toBe('FX_FORWARD')
    }
  })
})

describe('FX spot helpers (#10896)', () => {
  it('rejects a pair where both or neither leg is CZK', () => {
    expect(isValidFxPair('EUR', 'CZK')).toBe(true)
    expect(isValidFxPair('CZK', 'EUR')).toBe(true)
    expect(isValidFxPair('CZK', 'CZK')).toBe(false)
    expect(isValidFxPair('EUR', 'EUR')).toBe(false)
  })

  it('derives the foreign currency from a valid pair, and null from an invalid one', () => {
    expect(fxForeignCurrency('EUR', 'CZK')).toBe('EUR')
    expect(fxForeignCurrency('CZK', 'EUR')).toBe('EUR')
    expect(fxForeignCurrency('CZK', 'CZK')).toBeNull()
  })

  it('computes the CZK counter amount half-up to 2 dp, mirroring Deal.counterAmountOf', () => {
    expect(fxCounterAmount(1000, 25.105)).toBeCloseTo(25105, 5)
    expect(fxCounterAmount(1000, 25.005)).toBeCloseTo(25005, 5) // half-up, not banker's rounding
    expect(fxCounterAmount(Number.NaN, 25)).toBeNull()
  })

  it('computes T+2 business days, skipping weekends (mirrors DayCount.spotDate)', () => {
    // Wed 2026-09-23 -> Fri 2026-09-25 (no weekend in between)
    expect(fxSpotDate('2026-09-23')).toBe('2026-09-25')
    // Thu 2026-09-24 -> Mon 2026-09-28 (Sat/Sun skipped)
    expect(fxSpotDate('2026-09-24')).toBe('2026-09-28')
    // Fri 2026-09-25 -> Tue 2026-09-29
    expect(fxSpotDate('2026-09-25')).toBe('2026-09-29')
  })
})
