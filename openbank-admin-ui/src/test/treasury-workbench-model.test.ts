// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0344 treasury workbench: the pure rules behind the pages — ticket validation and request
// building for all 16 products, how a 422 maps back onto fields, the reference-kind catalogue,
// period presets, analysis grouping, and the four-eyes courtesies.
import { describe, expect, it } from 'vitest'
import { ALL_PRODUCTS, LEGACY_PRODUCTS, profileOf, productName, type AnyProduct } from '@/components/treasury/wb/catalogue'
import {
  buildRequest, emptyLeg, emptyTicket, errorFor, fieldForMissingReference, parseCustomSchedule, refusalErrors, ticketFromDeal,
  validateLeg, validateTicket, type TicketForm,
} from '@/components/treasury/wb/ticket'
import { KINDS, KIND_BY_ID, keyOf, toFieldValues, toPayload } from '@/components/treasury/wb/referenceKinds'
import { dealFilterQuery, filtersFromSaved, NO_DEAL_FILTERS, parseCsv, presetRange } from '@/components/treasury/wb/model'
import { groupByDate } from '@/components/treasury/wb/analysis'
import { canReleasePayment, nettingGroups } from '@/components/treasury/wb/payments'
import { isAiPrincipal, mayDecide } from '@/components/treasury/wb/principal'
import { dataGapOf, provenanceOf, responseProvenance } from '@/components/treasury/wb/reports'
import { normaliseAnalysis, ticketDealSchema, type Payment } from '@/components/treasury/wb/types'

const t = (cs: string, _en: string) => cs
const tEn = (_cs: string, en: string) => en
const TODAY = '2026-10-19'

/** A form that satisfies every client rule for [product]. */
function validForm(product: AnyProduct): TicketForm {
  const f = emptyTicket(product, TODAY)
  f.counterpartyId = product === 'CNB_DEPOSIT_FACILITY' || product === 'CNB_LOMBARD' ? 'CNB' : 'SIM-A'
  f.bookId = 'BANKING'
  const leg = (l: TicketForm['legs'][number]) => ({ ...l, notional: '1000000', accrualStart: '2026-10-21', accrualEnd: '2027-10-21', rate: l.rateType === 'FIXED' ? '4.25' : l.rate, index: l.rateType === 'FLOATING' ? 'PRIBOR_3M' : l.index })
  f.legs = f.legs.map(leg)
  if (profileOf(product).legacy) {
    f.principal = '1000000'
    f.rate = '4.25'
    if (product === 'FX_SPOT') { f.buyCurrency = 'EUR'; f.sellCurrency = 'CZK'; f.valueDate = '' }
  }
  if (product === 'TREASURY_BILL' || product === 'BOND' || product === 'BILL_OF_EXCHANGE') { f.securityIsin = 'CZ0001000000'; f.nominal = '5000000'; f.priceValue = '99.1' }
  if (product === 'FX_FORWARD' || product === 'FX_SWAP') {
    f.fxBuyAmount = '1000000'; f.fxSellAmount = '25000000'; f.fxRate = '25'; f.farDate = '2027-01-21'; f.nearDate = '2026-10-21'; f.nearRate = '25'; f.farRate = '25.2'
  }
  if (product === 'FRA') f.fraPeriod = '3x6'
  if (product === 'REPO' || product === 'REVERSE_REPO') { f.collIsin = 'CZ0001000000'; f.collNominal = '1000000'; f.collHaircut = '2' }
  return f
}

describe('catalogue', () => {
  it('offers exactly the 16 products of the contract, five of them legacy', () => {
    expect(ALL_PRODUCTS).toHaveLength(16)
    expect(new Set(ALL_PRODUCTS).size).toBe(16)
    expect(LEGACY_PRODUCTS).toHaveLength(5)
    for (const p of ALL_PRODUCTS) expect(productName(p, t)).not.toBe(p)
  })

  it('needs a book for every product beyond the legacy five, and legs for IRS (2), FRA, repo, loan', () => {
    for (const p of ALL_PRODUCTS) expect(profileOf(p).needsBook).toBe(!LEGACY_PRODUCTS.includes(p))
    expect(profileOf('IRS').legs).toBe(2)
    for (const p of ['FRA', 'REPO', 'REVERSE_REPO', 'LOAN', 'CASH'] as const) expect(profileOf(p).legs).toBe(1)
    for (const p of ['IRS', 'FRA', 'REPO', 'REVERSE_REPO', 'LOAN', 'FX_FORWARD', 'FX_SWAP'] as const) expect(profileOf(p).sendLegs).toBe(true)
  })
})

describe('ticket validation and request building — every product', () => {
  it.each(ALL_PRODUCTS)('%s: a complete form validates and builds a request carrying its product', product => {
    const f = validForm(product)
    expect(validateTicket(f, t)).toEqual({})
    const req = buildRequest(f)
    expect(req.product).toBe(product)
    expect(req.counterpartyId).toBe(f.counterpartyId)
    if (profileOf(product).sendLegs) expect(Array.isArray(req.legs) && (req.legs as unknown[]).length).toBeGreaterThanOrEqual(profileOf(product).legs || 2)
    if (profileOf(product).legacy) expect(req).not.toHaveProperty('legs')
  })

  it.each(ALL_PRODUCTS)('%s: an empty form is refused with field errors, never submitted blank', product => {
    expect(Object.keys(validateTicket(emptyTicket(product, TODAY), t)).length).toBeGreaterThan(0)
  })

  it('keeps the legacy flat shape and sends no ADR-0344 field that was not set', () => {
    const req = buildRequest(validForm('MM_PLACEMENT'))
    expect(req).toEqual({ product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25, valueDate: TODAY, bookId: 'BANKING', jurisdiction: 'CZ' })
  })

  it('omits maturity for ČNB facilities and valueDate for an FX spot left empty', () => {
    expect(buildRequest({ ...validForm('CNB_LOMBARD'), maturityDate: '2026-10-30' })).not.toHaveProperty('maturityDate')
    const fx = buildRequest(validForm('FX_SPOT'))
    expect(fx).not.toHaveProperty('valueDate')
    expect(fx).toMatchObject({ buyCurrency: 'EUR', sellCurrency: 'CZK', principal: 1000000, rate: 4.25 })
  })

  it('refuses an FX spot pair that is not exactly one CZK, and a value date beyond T+2', () => {
    expect(validateTicket({ ...validForm('FX_SPOT'), buyCurrency: 'CZK', sellCurrency: 'CZK' }, tEn).buyCurrency).toMatch(/CZK/)
    expect(validateTicket({ ...validForm('FX_SPOT'), buyCurrency: 'EUR', sellCurrency: 'USD' as never }, tEn).buyCurrency).toBeTruthy()
    expect(validateTicket({ ...validForm('FX_SPOT'), valueDate: '2099-01-01' }, tEn).valueDate).toMatch(/T\+2/)
  })

  it('builds an IRS with a fixed pay leg and a floating receive leg, index and spread on the wire', () => {
    const f = validForm('IRS')
    f.legs[1].spread = '0.15'
    f.legs[1].cap = '6'
    f.legs[1].floor = '0'
    f.legs[1].capFloorAppliesTo = 'INDEX_PLUS_SPREAD'
    f.legs[1].rateUntilFirstFixing = '4.1'
    const req = buildRequest(f) as { legs: Record<string, unknown>[] }
    expect(req.legs[0]).toMatchObject({ payReceive: 'PAY', rate: { type: 'FIXED', rate: 4.25 } })
    expect(req.legs[1]).toMatchObject({ payReceive: 'RECEIVE', rate: { type: 'FLOATING', index: 'PRIBOR_3M', spread: 0.15, cap: 6, floor: 0, capFloorAppliesTo: 'INDEX_PLUS_SPREAD', rateUntilFirstFixing: 4.1 } })
  })

  it('rejects an IRS whose legs are both pay legs, and a cap below the floor', () => {
    const f = validForm('IRS')
    f.legs[1].payReceive = 'PAY'
    expect(validateTicket(f, tEn)['legs.1.payReceive']).toMatch(/one pay leg and one receive leg/)
    const g = validForm('IRS')
    g.legs[1].cap = '1'
    g.legs[1].floor = '2'
    expect(validateTicket(g, tEn)['legs.1.cap']).toMatch(/cap may not be below/)
  })

  it('validates a leg: notional, accrual window, floating index, custom amortisation lines', () => {
    const bad = { ...emptyLeg('PAY'), rateType: 'FLOATING' as const, amortisation: 'CUSTOM', amortisationLines: 'nonsense' }
    const e = validateLeg(bad, 0, tEn)
    expect(e['legs.0.notional']).toBeTruthy()
    expect(e['legs.0.accrualStart']).toBeTruthy()
    expect(e['legs.0.index']).toBeTruthy()
    expect(e['legs.0.amortisationLines']).toBeTruthy()
    expect(validateLeg({ ...bad, notional: '1', accrualStart: '2026-10-21', accrualEnd: '2026-10-20', index: 'X', amortisationLines: '2027-01-01;10' }, 0, tEn)['legs.0.accrualEnd']).toMatch(/after the start/)
    expect(parseCustomSchedule('2027-01-01;10\n2027-02-01,20')).toEqual([{ date: '2027-01-01', amount: 10 }, { date: '2027-02-01', amount: 20 }])
    expect(parseCustomSchedule('2027-13-01;10')).toBeNull()
  })

  it('checks product panels: ISIN and one price, FX dates, FRA period, repo haircut, fees, links, LEI', () => {
    expect(validateTicket({ ...validForm('BOND'), securityIsin: 'bad' }, tEn).securityIsin).toBeTruthy()
    expect(validateTicket({ ...validForm('FX_SWAP'), nearDate: '2027-02-01' }, tEn).farDate).toMatch(/after the near/)
    expect(validateTicket({ ...validForm('FRA'), fraPeriod: '36' }, tEn).fraPeriod).toBeTruthy()
    expect(validateTicket({ ...validForm('REPO'), collHaircut: '100' }, tEn).collHaircut).toBeTruthy()
    const f = validForm('MM_PLACEMENT')
    f.fees = [{ type: '', amount: '-1', currency: 'CZK', paymentDate: '' }]
    f.linkedDeals = [{ dealId: 'not-a-uuid', relation: 'HEDGE' }]
    f.counterpartyLei = 'short'
    const e = validateTicket(f, tEn)
    expect(e['fees.0.type']).toBeTruthy()
    expect(e['fees.0.amount']).toBeTruthy()
    expect(e['fees.0.paymentDate']).toBeTruthy()
    expect(e['linkedDeals.0.dealId']).toBeTruthy()
    expect(e.counterpartyLei).toMatch(/20 characters/)
  })

  it('sends securities as clean price OR yield, never both, and builds two bullet FX legs from the terms', () => {
    const bond = buildRequest({ ...validForm('BOND'), priceMode: 'YIELD', priceValue: '4.1' }) as Record<string, unknown>
    expect(bond.yield).toBe(4.1)
    expect(bond).not.toHaveProperty('cleanPrice')
    const swap = buildRequest(validForm('FX_SWAP')) as { legs: Record<string, unknown>[] }
    expect(swap.legs).toHaveLength(2)
    expect(swap.legs[0]).toMatchObject({ payReceive: 'PAY', currency: 'CZK', notional: 25000000, initialNotionalExchange: true, finalNotionalExchange: true })
    expect(swap.legs[1]).toMatchObject({ payReceive: 'RECEIVE', currency: 'EUR', notional: 1000000 })
  })

  it('carries header, regulatory, accounts, documents, fees and links when set', () => {
    const f = validForm('MM_PLACEMENT')
    Object.assign(f, {
      kind: 'PLANNED', legalEntityId: 'BANK', counterpartyReference: 'CP-1', tradeDateTime: '2026-10-19T10:30', calendars: 'CERTIS, TARGET2',
      cashAccountId: 'NOSTRO-CZK', uti: 'UTI1', counterpartyLei: '5493001KJTIIGC8Y1R12', clearingObligation: 'true', collateralisation: 'FULLY',
      centralBankCodes: 'A1, B2', lcrCategory: 'L1', nsfrCategory: 'N2', note: 'n',
    })
    f.documents = [{ documentId: 'doc-1', type: 'CONFIRMATION' }]
    f.fees = [{ type: 'BROKERAGE', amount: '100', currency: 'CZK', paymentDate: '2026-10-21' }]
    f.linkedDeals = [{ dealId: '0b7c0b7c-0b7c-4b7c-8b7c-0b7c0b7c0b7c', relation: 'HEDGE' }]
    expect(validateTicket(f, t)).toEqual({})
    expect(buildRequest(f)).toMatchObject({
      kind: 'PLANNED', legalEntityId: 'BANK', counterpartyReference: 'CP-1', calendars: ['CERTIS', 'TARGET2'],
      settlementAccounts: { cashAccountId: 'NOSTRO-CZK' },
      regulatory: { tradeReporting: { uti: 'UTI1', clearingObligation: true, collateralisation: 'FULLY' }, centralBankCodes: ['A1', 'B2'], lcrCategory: 'L1', nsfrCategory: 'N2' },
      documents: [{ documentId: 'doc-1', type: 'CONFIRMATION' }], fees: [{ type: 'BROKERAGE', amount: 100, currency: 'CZK', paymentDate: '2026-10-21' }],
      linkedDeals: [{ dealId: '0b7c0b7c-0b7c-4b7c-8b7c-0b7c0b7c0b7c', relation: 'HEDGE' }],
    })
  })

  it('round-trips a stored deal into the form', () => {
    const parsed = ticketDealSchema.parse({
      dealId: 'd1', product: 'IRS', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25, dayCount: 'ACT/360', days: 365,
      interest: 1, tradeDate: '2026-10-19', valueDate: '2026-10-21', maturityDate: '2027-10-21', state: 'DRAFT', createdBy: 'a', createdByType: 'HUMAN',
      submittedBy: null, approvedBy: null, rationale: null, limitCheck: null, createdAt: 'x', updatedAt: 'x', history: [], journals: [], bookId: 'BANKING',
      legs: [
        { payReceive: 'PAY', currency: 'CZK', notional: 1000000, accrualStart: '2026-10-21', accrualEnd: '2027-10-21', dayCount: 'ACT/360', rate: { type: 'FIXED', rate: 4.25 } },
        { payReceive: 'RECEIVE', currency: 'CZK', notional: 1000000, accrualStart: '2026-10-21', accrualEnd: '2027-10-21', dayCount: 'ACT/360', rate: { type: 'FLOATING', index: 'PRIBOR_3M', spread: 0.1 } },
      ],
    })
    const f = ticketFromDeal(parsed)
    expect(f.bookId).toBe('BANKING')
    expect(f.legs).toHaveLength(2)
    expect(f.legs[1]).toMatchObject({ rateType: 'FLOATING', index: 'PRIBOR_3M', spread: '0.1' })
    expect(validateTicket(f, t)).toEqual({})
  })
})

describe('server refusals land on the field they concern', () => {
  it('REFERENCE_NOT_EFFECTIVE names each missing reference on its field', () => {
    const r = { status: 422, code: 'REFERENCE_NOT_EFFECTIVE', message: null, missingReferences: ['book:BANKING', 'calendar:CERTIS', 'index:PRIBOR_3M', 'security:CZ0001000000'], breaches: [] }
    const e = refusalErrors(r, tEn)
    expect(e.bookId).toMatch(/book:BANKING/)
    expect(e.calendars).toMatch(/calendar:CERTIS/)
    expect(e['legs.index']).toMatch(/index:PRIBOR_3M/)
    expect(e.securityIsin).toMatch(/security:CZ0001000000/)
    expect(errorFor(e, 'legs.1.index')).toBe(e['legs.index'])
    expect(fieldForMissingReference('mystery:X')).toBe('_form')
  })

  it('PRODUCT_LIMIT_BREACHED explains each breached rule on principal, currency and maturity', () => {
    const r = {
      status: 422, code: 'PRODUCT_LIMIT_BREACHED', message: null, missingReferences: [],
      breaches: [{ rule: 'MAX_PRINCIPAL', limit: '5000000', actual: '9000000' }, { rule: 'CURRENCY_NOT_PERMITTED', limit: 'CZK', actual: 'EUR' }, { rule: 'MAX_TENOR', limit: '90', actual: '120' }],
    }
    const e = refusalErrors(r, tEn)
    expect(e.principal).toMatch(/5000000/)
    expect(e.currency).toMatch(/EUR/)
    expect(e.maturityDate).toMatch(/90/)
  })

  it('a FOUR_EYES_VIOLATION carries no field (it is an action-level message)', () => {
    expect(refusalErrors({ status: 422, code: 'FOUR_EYES_VIOLATION', message: 'x', missingReferences: [], breaches: [] }, tEn)).toEqual({})
  })
})

describe('reference-data catalogue', () => {
  it('covers all 32 kinds of the contract (31 + internal rate sheets), each with a key field and at least one field', () => {
    expect(KINDS).toHaveLength(32)
    expect(new Set(KINDS.map(k => k.kind)).size).toBe(32)
    for (const must of ['calendar', 'index', 'counterparty', 'ssi', 'netting', 'security', 'book', 'bucket-set', 'regulatory-code', 'party-group', 'party-type', 'country',
      'document-type', 'account', 'settlement-place', 'product-type', 'loan-type', 'communication-channel', 'market-factor', 'own-capital-item', 'period-length',
      'scenario', 'behavioural-scenario', 'calculation-settings', 'limit-settings', 'derivative-conversion-factor', 'collateral-haircut', 'portfolio-group',
      'accounting-settings', 'balance-sheet-side', 'dealing-filter']) expect(KIND_BY_ID[must], must).toBeTruthy()
    for (const k of KINDS) { expect(k.fields.length).toBeGreaterThan(0); expect(k.keyFields.length).toBeGreaterThan(0) }
  })

  it('derives the key the way the contract states it (ssi counterparty:currency, regulatory-code list:code)', () => {
    expect(keyOf(KIND_BY_ID.ssi, { counterpartyId: 'SIM-A', currency: 'EUR' })).toBe('SIM-A:EUR')
    expect(keyOf(KIND_BY_ID['regulatory-code'], { list: 'LCR', code: 'L1' })).toBe('LCR:L1')
    expect(keyOf(KIND_BY_ID.security, { isin: '' })).toBe('')
  })

  it('turns editor strings into a typed payload and back (lists, maps, buckets, numbers, dates)', () => {
    const cp = KIND_BY_ID.counterparty
    const v = toFieldValues(cp, { id: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', countryCode: 'CZ', limits: { CZK: 5000000, EUR: 200000 } })
    expect(v.limits).toBe('CZK=5000000\nEUR=200000')
    const r = toPayload(cp, v, tEn)
    expect(r.errors).toEqual({})
    expect(r.payload).toMatchObject({ id: 'SIM-A', limits: { CZK: 5000000, EUR: 200000 }, lei: null })
    const bs = toPayload(KIND_BY_ID['bucket-set'], { id: 'b', name: 'n', buckets: '0-1M;0;30\n>1M;31;' }, tEn)
    expect(bs.payload.buckets).toEqual([{ label: '0-1M', fromDays: 0, toDays: 30 }, { label: '>1M', fromDays: 31, toDays: null }])
    const cal = toPayload(KIND_BY_ID.calendar, { code: 'X', baseCalendar: 'CERTIS', extraHolidays: '2026-12-24, 2026-12-31', removedHolidays: '' }, tEn)
    expect(cal.payload.extraHolidays).toEqual(['2026-12-24', '2026-12-31'])
  })

  it('reports required, number, date and line-format errors per field', () => {
    const idx = toPayload(KIND_BY_ID.index, { code: '', currency: 'CZK', tenor: '3M', dayCount: 'ACT/360', fixingLagBusinessDays: 'x', fixingCalendar: 'CERTIS', source: '' }, tEn)
    expect(idx.errors.code).toBeTruthy()
    expect(idx.errors.fixingLagBusinessDays).toMatch(/whole number/)
    expect(idx.errors.source).toBeTruthy()
    expect(toPayload(KIND_BY_ID.calendar, { code: 'X', baseCalendar: 'CERTIS', extraHolidays: '24.12.2026' }, tEn).errors.extraHolidays).toBeTruthy()
    expect(toPayload(KIND_BY_ID.counterparty, { id: 'a', name: 'b', kind: 'BANK', countryCode: 'CZ', limits: 'CZK:1' }, tEn).errors.limits).toBeTruthy()
    expect(toPayload(KIND_BY_ID.security, { isin: 'x', name: 'n', issuerId: 'i', type: 'BOND', currency: 'CZK', issueDate: '2026/01/01' }, tEn).errors.issueDate).toBeTruthy()
  })
})

describe('views and presets', () => {
  it('presets cover yesterday, today, tomorrow, a week and a month around the bank date', () => {
    expect(presetRange('yesterday', TODAY)).toEqual({ from: '2026-10-18', to: '2026-10-18' })
    expect(presetRange('today', TODAY)).toEqual({ from: TODAY, to: TODAY })
    expect(presetRange('tomorrow', TODAY)).toEqual({ from: '2026-10-20', to: '2026-10-20' })
    expect(presetRange('week', TODAY)).toEqual({ from: TODAY, to: '2026-10-25' })
    expect(presetRange('month', TODAY)).toEqual({ from: TODAY, to: '2026-11-17' })
  })

  it('sends only the deal filters that are set, state first, booleans only when true', () => {
    expect(dealFilterQuery(NO_DEAL_FILTERS)).toEqual({})
    const q = dealFilterQuery({ ...NO_DEAL_FILTERS, state: 'BOOKED', text: ' abc ', hideMatured: true })
    expect(Object.keys(q)[0]).toBe('state')
    expect(q).toEqual({ state: 'BOOKED', hideMatured: 'true', text: 'abc' })
    expect(filtersFromSaved({ state: 'BOOKED', hideMatured: 'true', junk: 1, currency: 5 })).toMatchObject({ state: 'BOOKED', hideMatured: true, currency: '' })
  })

  it('reads CSV with quotes and either separator', () => {
    expect(parseCsv('index,date,value\nPRIBOR_3M,2026-10-19,4.1\n"A,B",2026-10-19,"1,5"')).toEqual([
      { index: 'PRIBOR_3M', date: '2026-10-19', value: '4.1' }, { index: 'A,B', date: '2026-10-19', value: '1,5' },
    ])
    expect(parseCsv('index;value\nX;1')).toEqual([{ index: 'X', value: '1' }])
    expect(parseCsv('only a header')).toEqual([])
  })

  it('groups analysis flows per date or month with a running total', () => {
    const f = (date: string, amount: number) => ({ date, currency: 'CZK', amount, cumulative: null, dealId: 'd', legIndex: 0, kind: null })
    const daily = groupByDate([f('2026-11-02', 100), f('2026-10-30', -40), f('2026-11-02', 50)], false)
    expect(daily.map(r => [r.key, r.total, r.cumulative])).toEqual([['2026-10-30', -40, -40], ['2026-11-02', 150, 110]])
    expect(groupByDate([f('2026-10-30', 1), f('2026-10-31', 2), f('2026-11-01', 4)], true).map(r => [r.key, r.total])).toEqual([['2026-10', 3], ['2026-11', 4]])
  })

  it('accepts the analysis result as a flat list, flows, or per-currency groups; rejects anything else', () => {
    const flow = { date: '2026-11-02', amount: '100.5', currency: 'EUR', dealId: 'd', legIndex: 1 }
    expect(normaliseAnalysis([flow])?.currencies[0].flows[0]).toMatchObject({ amount: 100.5, currency: 'EUR', legIndex: 1 })
    expect(normaliseAnalysis({ flows: [flow] })?.currencies).toHaveLength(1)
    expect(normaliseAnalysis({ currencies: [{ currency: 'CZK', flows: [{ date: '2026-11-02', amount: 1 }] }] })?.currencies[0].currency).toBe('CZK')
    expect(normaliseAnalysis({ nothing: true })).toBeNull()
    expect(normaliseAnalysis('x')).toBeNull()
  })
})

describe('four-eyes courtesies', () => {
  const pay = (over: Partial<Payment> = {}): Pick<Payment, 'status' | 'createdBy' | 'dealCreators'> => ({ status: 'PROPOSED', createdBy: 'dana.dealer', dealCreators: ['dana.dealer'], ...over })

  it('release is offered to a releaser who is neither the deal creator nor an AI principal, on a PROPOSED payment', () => {
    expect(canReleasePayment(pay(), { actor: 'rita.releaser', holdsRelease: true, ai: false })).toBe(true)
    expect(canReleasePayment(pay(), { actor: 'dana.dealer', holdsRelease: true, ai: false })).toBe(false)
    expect(canReleasePayment(pay({ createdBy: null, dealCreators: ['dana.dealer'] }), { actor: 'dana.dealer', holdsRelease: true, ai: false })).toBe(false)
    expect(canReleasePayment(pay(), { actor: 'rita.releaser', holdsRelease: true, ai: true })).toBe(false)
    expect(canReleasePayment(pay(), { actor: 'rita.releaser', holdsRelease: false, ai: false })).toBe(false)
    expect(canReleasePayment(pay({ status: 'SENT' }), { actor: 'rita.releaser', holdsRelease: true, ai: false })).toBe(false)
  })

  it('mayDecide withholds the proposer’s own item and every AI principal', () => {
    expect(mayDecide('dana', 'petr', false)).toBe(true)
    expect(mayDecide('dana', 'dana', false)).toBe(false)
    expect(mayDecide('dana', 'petr', true)).toBe(false)
    expect(mayDecide(null, 'petr', false)).toBe(true)
  })

  it('recognises an AI principal by actor type or role', () => {
    expect(isAiPrincipal({ actorType: 'AI_AGENT' })).toBe(true)
    expect(isAiPrincipal({ roles: ['ROLE_AI_AGENT'] })).toBe(true)
    expect(isAiPrincipal({ roles: ['ROLE_TREASURY_APPROVER'] })).toBe(false)
    expect(isAiPrincipal(undefined)).toBe(false)
  })

  it('nets same-counterparty same-day same-currency payments, OUT negative and IN positive', () => {
    const p = (id: string, direction: string, amount: number, status = 'PROPOSED') => ({ paymentId: id, status, direction, counterpartyId: 'SIM-A', currency: 'CZK', amount, valueDate: '2026-10-21' }) as Payment
    expect(nettingGroups([p('1', 'OUT', 100), p('2', 'IN', 30), p('3', 'OUT', 5, 'CANCELLED')])).toEqual([
      { key: 'SIM-A|2026-10-21|CZK', counterparty: 'SIM-A', valueDate: '2026-10-21', currency: 'CZK', count: 2, net: -70 },
    ])
    expect(nettingGroups([p('1', 'OUT', 100)])).toEqual([])
  })
})

describe('report provenance and data gaps', () => {
  it('maps whatever a response calls its provenance and falls back to the report default', () => {
    expect(provenanceOf('production')).toBe('regulatory')
    expect(provenanceOf('SYNTHETIC')).toBe('synthetic')
    expect(provenanceOf('weird', 'intraday')).toBe('intraday')
    expect(provenanceOf(undefined)).toBeNull()
    expect(responseProvenance({ header: { provenance: 'synthetic' } })).toBe('synthetic')
    expect(responseProvenance({ provenance: 'intraday' })).toBe('intraday')
    expect(responseProvenance([], 'regulatory')).toBe('regulatory')
  })

  it('reads a data gap only when the service states one', () => {
    expect(dataGapOf({ dataGap: 'only 12 of 250 scenarios' })).toBe('only 12 of 250 scenarios')
    expect(dataGapOf({ dataGap: null })).toBeNull()
    expect(dataGapOf({ dataGap: '  ' })).toBeNull()
    expect(dataGapOf(null)).toBeNull()
  })
})
