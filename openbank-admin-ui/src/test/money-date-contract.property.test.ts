// SPDX-License-Identifier: Apache-2.0
// ADR-0370 U4 — property tests for the formatting and parsing that money and dates pass through.
//
// Example tests check the amounts someone thought of. These check every amount fast-check can
// generate, and shrink a failure to the smallest one: the defect class is a rounding, sign or
// calendar edge that no hand-picked example hits. Seeds are fixed so a failure reproduces.

import fc from 'fast-check'
import { describe, expect, it } from 'vitest'
import { formatMoneyCompact, formatMoneyExact } from '@/lib/format/money'
import { bankToday, isIsoDate } from '@/lib/format/bankDate'
import { formatIban } from '@/lib/utils'
import { FeeScheduleContractError, parseFeeSchedule } from '@/lib/fees/feeScheduleContract'
import { parseInterestAccruals } from '@/lib/interest/interestAccrualContract'

const RUNS = { numRuns: 500, seed: 370 }
const LOCALES = ['cs-CZ', 'en-US', 'de-DE', 'en-GB'] as const
const CURRENCIES = ['CZK', 'EUR', 'USD', 'GBP'] as const

// Integer haléř/cents, the unit a posting reconciles to. ±10^13 keeps every value exactly
// representable as a double after the /100 below.
const cents = fc.integer({ min: -1e13, max: 1e13 })

/** The digits a reader sees, in order, with the sign — i.e. the amount in cents as printed. */
function printedCents(text: string): number {
  const negative = /[-−]/.test(text)
  const digits = text.replace(/[^0-9]/g, '')
  return (negative ? -1 : 1) * Number(digits)
}

describe('formatMoneyExact', () => {
  it('prints exactly the cents it was given, in every locale and currency', () => {
    fc.assert(
      fc.property(cents, fc.constantFrom(...LOCALES), fc.constantFrom(...CURRENCIES), (c, locale, currency) => {
        expect(printedCents(formatMoneyExact(c / 100, currency, locale))).toBe(c === 0 ? 0 : c)
      }),
      RUNS,
    )
  })

  it('always shows two fraction digits', () => {
    fc.assert(
      fc.property(cents, fc.constantFrom(...LOCALES), (c, locale) => {
        const text = formatMoneyExact(c / 100, 'CZK', locale)
        expect(text).toMatch(/\d[.,]\d{2}(?!\d)/)
      }),
      RUNS,
    )
  })

  it('never throws on an unknown currency code and still names it', () => {
    const code = fc.stringMatching(/^[A-Z]{2}[0-9]$/)
    fc.assert(
      fc.property(cents, fc.constantFrom(...LOCALES), code, (c, locale, currency) => {
        const text = formatMoneyExact(c / 100, currency, locale)
        expect(text).toContain(currency)
        expect(printedCents(text.replace(currency, ''))).toBe(c === 0 ? 0 : c)
      }),
      RUNS,
    )
  })
})

describe('formatMoneyCompact', () => {
  it('never throws and keeps the sign', () => {
    fc.assert(
      fc.property(cents, fc.constantFrom(...LOCALES), fc.constantFrom(...CURRENCIES), (c, locale, currency) => {
        const text = formatMoneyCompact(c / 100, currency, locale)
        expect(/[-−]/.test(text)).toBe(c / 100 <= -0.005)
      }),
      RUNS,
    )
  })
})

describe('isIsoDate / bankToday', () => {
  const day = fc.date({ min: new Date('1900-01-01T00:00:00Z'), max: new Date('2199-12-31T23:59:59Z'), noInvalidDate: true })

  it('accepts every real calendar day in canonical form', () => {
    fc.assert(fc.property(day, d => { expect(isIsoDate(d.toISOString().slice(0, 10))).toBe(true) }), RUNS)
  })

  it('rejects a day past the end of its month', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1900, max: 2199 }), fc.integer({ min: 1, max: 12 }), (year, month) => {
        const last = new Date(Date.UTC(year, month, 0)).getUTCDate()
        const mm = String(month).padStart(2, '0')
        expect(isIsoDate(`${year}-${mm}-${String(last).padStart(2, '0')}`)).toBe(true)
        if (last < 31) expect(isIsoDate(`${year}-${mm}-${String(last + 1).padStart(2, '0')}`)).toBe(false)
      }),
      RUNS,
    )
  })

  it('bankToday is a real day within one day of the UTC date', () => {
    fc.assert(
      fc.property(day, d => {
        const today = bankToday(d)
        expect(isIsoDate(today)).toBe(true)
        const deltaDays = (Date.parse(`${today}T00:00:00Z`) - Date.parse(`${d.toISOString().slice(0, 10)}T00:00:00Z`)) / 86_400_000
        expect([0, 1]).toContain(deltaDays)
      }),
      RUNS,
    )
  })
})

describe('formatIban', () => {
  it('only inserts spaces, in groups of four', () => {
    const iban = fc.stringMatching(/^[A-Z]{2}[0-9]{2}[A-Z0-9]{8,30}$/)
    fc.assert(
      fc.property(iban, raw => {
        const text = formatIban(raw)
        expect(text.replace(/ /g, '')).toBe(raw)
        for (const group of text.split(' ')) expect(group.length).toBeLessThanOrEqual(4)
        expect(text.split(' ').slice(0, -1).every(g => g.length === 4)).toBe(true)
      }),
      RUNS,
    )
  })
})

const nonBlank = fc.string({ minLength: 1, maxLength: 12 }).filter(s => s.trim() !== '')

const feeItem = fc.record({
  id: nonBlank, code: nonBlank, name: nonBlank, type: nonBlank,
  amount: fc.double({ min: 0, max: 1e9, noNaN: true, noDefaultInfinity: true }),
  currency: fc.constantFrom('czk', 'EUR', 'usd'), frequency: nonBlank,
  description: fc.option(nonBlank, { nil: null }), waivable: fc.boolean(),
  waiveCondition: fc.option(nonBlank, { nil: null }), waiverEvaluable: fc.constant(false), waiverRule: fc.constant(null),
  productId: nonBlank, productCode: nonBlank, productName: nonBlank,
  status: fc.constantFrom('ACTIVE', 'INACTIVE', 'DRAFT', 'DEPRECATED', 'ARCHIVED'), updatedAt: nonBlank,
})

describe('parseFeeSchedule', () => {
  it('preserves every valid amount exactly and upper-cases the currency', () => {
    fc.assert(
      fc.property(fc.array(feeItem, { maxLength: 5 }), items => {
        const parsed = parseFeeSchedule(items)
        expect(parsed.map(p => p.amount)).toEqual(items.map(i => i.amount))
        expect(parsed.every(p => p.currency === p.currency.toUpperCase())).toBe(true)
      }),
      RUNS,
    )
  })

  it('rejects any negative or non-finite amount', () => {
    const bad = fc.oneof(
      fc.double({ max: -Number.MIN_VALUE, noNaN: true }),
      fc.constantFrom(Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY),
    )
    fc.assert(
      fc.property(feeItem, bad, (item, amount) => {
        expect(() => parseFeeSchedule([{ ...item, amount }])).toThrow(FeeScheduleContractError)
      }),
      RUNS,
    )
  })
})

describe('parseInterestAccruals', () => {
  it('reads a numeric string as the same number the service sent', () => {
    const amount = fc.double({ min: -1e9, max: 1e9, noNaN: true, noDefaultInfinity: true })
    fc.assert(
      fc.property(amount, value => {
        const [parsed] = parseInterestAccruals([{
          id: 'a', accountId: 'b', accrualDate: '2026-11-11', accruedAmount: String(value),
          currency: 'czk', rate: value, dayCount: 'ACT_365', status: 'ACCRUING',
        }])
        // `===`, not Object.is: String(-0) is "0", and a sign on zero carries no money.
        expect(parsed?.accruedAmount === value).toBe(true)
        expect(parsed?.rate).toBe(value)
      }),
      RUNS,
    )
  })
})
