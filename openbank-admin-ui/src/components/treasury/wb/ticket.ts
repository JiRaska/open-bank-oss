// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The deal ticket as form state: defaults, client-side validation that mirrors the server's
// rules (the server stays the control), the DraftDealRequest it builds, and the mapping of a 422
// back onto the field it concerns. Pure — unit-tested without rendering.
import { bankToday, isIsoDate } from '@/lib/format/bankDate'
import { fxSpotDate } from '@/components/treasury/model'
import { profileOf, type AnyProduct } from './catalogue'
import type { Refusal } from './client'
import type { TicketDeal } from './types'

type T = (cs: string, en: string) => string

export type LegForm = {
  payReceive: 'PAY' | 'RECEIVE'
  currency: string
  notional: string
  amortisation: string
  amortisationStart: string
  amortisationLines: string // CUSTOM: one "YYYY-MM-DD;amount" per line
  accrualStart: string
  accrualEnd: string
  accrualFrequency: string
  paymentFrequency: string
  stub: string
  adjustPaymentDate: boolean
  adjustAccrualPeriod: boolean
  paymentLagBusinessDays: string
  paymentCalendars: string
  dayCount: string
  rateType: 'FIXED' | 'FLOATING' | 'NONE'
  rate: string
  index: string
  spread: string
  fixingLagBusinessDays: string
  resetFrequency: string
  rateUntilFirstFixing: string
  cap: string
  floor: string
  capFloorAppliesTo: string
  initialNotionalExchange: boolean
  finalNotionalExchange: boolean
}

export type FeeForm = { type: string; amount: string; currency: string; paymentDate: string }
export type DocForm = { documentId: string; type: string }
export type LinkForm = { dealId: string; relation: string }

export type TicketForm = {
  product: AnyProduct
  kind: string
  counterpartyId: string
  legalEntityId: string
  counterpartyReference: string
  channel: string
  bookId: string
  tradeDateTime: string
  valueDate: string
  maturityDate: string
  validFrom: string
  validTo: string
  jurisdiction: string
  calendars: string
  businessDayConvention: string
  fixedConvention: boolean
  rollDirection: string
  notionalExchange: boolean
  singleCurrency: boolean
  note: string
  rationale: string
  // legacy flat terms
  currency: string
  principal: string
  rate: string
  buyCurrency: string
  sellCurrency: string
  // deposit / loan / cash
  principalAccountId: string
  interestAccountId: string
  earlyWithdrawalAllowed: boolean
  earlyWithdrawalNoticeDays: string
  earlyWithdrawalPenaltyBp: string
  earlyWithdrawalNote: string
  // securities
  securityIsin: string
  nominal: string
  priceMode: 'CLEAN_PRICE' | 'YIELD'
  priceValue: string
  accruedInterest: string
  settlementPlace: string
  custodyAccountId: string
  // fx
  fxBuyAmount: string
  fxSellAmount: string
  fxRate: string
  forwardPoints: string
  nearDate: string
  farDate: string
  nearRate: string
  farRate: string
  // fra
  fraPeriod: string
  fraSettlement: string
  // collateral
  collIsin: string
  collNominal: string
  collDirtyPrice: string
  collHaircut: string
  collSubstitution: boolean
  collMarginCall: string
  // tabs
  cashAccountId: string
  securitiesAccountId: string
  documents: DocForm[]
  fees: FeeForm[]
  linkedDeals: LinkForm[]
  originalDealId: string
  centralBankCodes: string
  lcrCategory: string
  nsfrCategory: string
  uti: string
  counterpartyLei: string
  clearingObligation: '' | 'true' | 'false'
  collateralisation: string
  legs: LegForm[]
}

export function emptyLeg(payReceive: 'PAY' | 'RECEIVE' = 'PAY', currency = 'CZK'): LegForm {
  return {
    payReceive, currency, notional: '', amortisation: 'BULLET', amortisationStart: '', amortisationLines: '',
    accrualStart: '', accrualEnd: '', accrualFrequency: 'QUARTERLY', paymentFrequency: 'QUARTERLY', stub: 'NONE',
    adjustPaymentDate: true, adjustAccrualPeriod: false, paymentLagBusinessDays: '0', paymentCalendars: '', dayCount: 'ACT/360',
    rateType: 'FIXED', rate: '', index: '', spread: '', fixingLagBusinessDays: '2', resetFrequency: 'QUARTERLY',
    rateUntilFirstFixing: '', cap: '', floor: '', capFloorAppliesTo: 'INDEX', initialNotionalExchange: false, finalNotionalExchange: false,
  }
}

export function emptyTicket(product: AnyProduct, today: string): TicketForm {
  const p = profileOf(product)
  const legs: LegForm[] = p.legs === 2
    ? [{ ...emptyLeg('PAY'), rateType: 'FIXED' }, { ...emptyLeg('RECEIVE'), rateType: 'FLOATING' }]
    : p.legs === 1 ? [{ ...emptyLeg(product === 'REPO' || product === 'FRA' ? 'RECEIVE' : 'PAY'), rateType: product === 'FRA' ? 'FLOATING' : 'FIXED' }] : []
  return {
    product, kind: 'ACTUAL', counterpartyId: '', legalEntityId: '', counterpartyReference: '', channel: 'VOICE', bookId: '',
    tradeDateTime: '', valueDate: product === 'FX_SPOT' ? '' : today, maturityDate: '', validFrom: '', validTo: '', jurisdiction: 'CZ', calendars: '',
    businessDayConvention: 'MODIFIED_FOLLOWING', fixedConvention: false, rollDirection: 'FROM_START', notionalExchange: false,
    singleCurrency: true, note: '', rationale: '', currency: 'CZK', principal: '', rate: '', buyCurrency: 'EUR', sellCurrency: 'CZK',
    principalAccountId: '', interestAccountId: '', earlyWithdrawalAllowed: false, earlyWithdrawalNoticeDays: '', earlyWithdrawalPenaltyBp: '', earlyWithdrawalNote: '',
    securityIsin: '', nominal: '', priceMode: 'CLEAN_PRICE', priceValue: '', accruedInterest: '', settlementPlace: '', custodyAccountId: '',
    fxBuyAmount: '', fxSellAmount: '', fxRate: '', forwardPoints: '', nearDate: '', farDate: '', nearRate: '', farRate: '',
    fraPeriod: '', fraSettlement: 'ADVANCE_DISCOUNTED',
    collIsin: '', collNominal: '', collDirtyPrice: '', collHaircut: '', collSubstitution: false, collMarginCall: '',
    cashAccountId: '', securitiesAccountId: '', documents: [], fees: [], linkedDeals: [], originalDealId: '',
    centralBankCodes: '', lcrCategory: '', nsfrCategory: '', uti: '', counterpartyLei: '', clearingObligation: '', collateralisation: '',
    legs,
  }
}

const str = (v: unknown): string => (v === null || v === undefined ? '' : String(v))

/** Pre-fill the form from a stored deal (edit mode). */
export function ticketFromDeal(d: TicketDeal): TicketForm {
  const product = (d.product as AnyProduct)
  const f = emptyTicket(product, d.valueDate)
  const leg = (l: NonNullable<TicketDeal['legs']>[number]): LegForm => ({
    ...emptyLeg(l.payReceive === 'RECEIVE' ? 'RECEIVE' : 'PAY', l.currency),
    notional: str(l.notional), amortisation: l.amortisation?.type ?? 'BULLET', amortisationStart: str(l.amortisationStart),
    amortisationLines: (l.amortisation?.schedule ?? []).map(s => `${s.date};${s.amount}`).join('\n'),
    accrualStart: l.accrualStart, accrualEnd: l.accrualEnd, accrualFrequency: str(l.accrualFrequency) || 'QUARTERLY',
    paymentFrequency: str(l.paymentFrequency) || 'QUARTERLY', stub: str(l.stub) || 'NONE', adjustPaymentDate: l.adjustPaymentDate ?? true,
    adjustAccrualPeriod: l.adjustAccrualPeriod ?? false, paymentLagBusinessDays: str(l.paymentLagBusinessDays ?? 0),
    paymentCalendars: (l.paymentCalendars ?? []).join(', '), dayCount: l.dayCount,
    rateType: (l.rate.type as LegForm['rateType']) ?? 'FIXED', rate: str(l.rate.rate), index: str(l.rate.index), spread: str(l.rate.spread),
    fixingLagBusinessDays: str(l.rate.fixingLagBusinessDays ?? 2), resetFrequency: str(l.rate.resetFrequency) || 'QUARTERLY',
    rateUntilFirstFixing: str(l.rate.rateUntilFirstFixing), cap: str(l.rate.cap), floor: str(l.rate.floor),
    capFloorAppliesTo: str(l.rate.capFloorAppliesTo) || 'INDEX', initialNotionalExchange: l.initialNotionalExchange ?? false,
    finalNotionalExchange: l.finalNotionalExchange ?? false,
  })
  const reg = d.regulatory
  return {
    ...f,
    kind: d.kind ?? 'ACTUAL', counterpartyId: d.counterpartyId, legalEntityId: str(d.legalEntityId), counterpartyReference: str(d.counterpartyReference),
    channel: d.channel ?? 'VOICE', bookId: str(d.bookId), tradeDateTime: d.tradeDateTime ? d.tradeDateTime.slice(0, 16) : '',
    valueDate: d.valueDate, maturityDate: d.maturityDate, validFrom: str(d.validFrom), validTo: str(d.validTo), jurisdiction: str(d.jurisdiction) || 'CZ',
    calendars: (d.calendars ?? []).join(', '), businessDayConvention: str(d.businessDayConvention) || 'MODIFIED_FOLLOWING',
    fixedConvention: d.fixedConvention ?? false, rollDirection: str(d.rollDirection) || 'FROM_START', notionalExchange: d.notionalExchange ?? false,
    singleCurrency: d.singleCurrency ?? true, note: str(d.note), rationale: str(d.rationale), currency: d.currency, principal: str(d.principal), rate: str(d.rate),
    buyCurrency: d.fx?.buyCurrency ?? f.buyCurrency, sellCurrency: d.fx?.sellCurrency ?? f.sellCurrency,
    securityIsin: str(d.securityTerms?.securityIsin ?? d.securityIsin), nominal: str(d.securityTerms?.nominal),
    priceMode: d.securityTerms?.yield != null ? 'YIELD' : 'CLEAN_PRICE', priceValue: str(d.securityTerms?.yield ?? d.securityTerms?.cleanPrice),
    accruedInterest: str(d.securityTerms?.accruedInterest), settlementPlace: str(d.securityTerms?.settlementPlace), custodyAccountId: str(d.securityTerms?.custodyAccountId),
    fxBuyAmount: str(d.fxTerms?.buyAmount), fxSellAmount: str(d.fxTerms?.sellAmount), fxRate: str(d.fxTerms?.rate), forwardPoints: str(d.fxTerms?.forwardPoints),
    nearDate: str(d.fxTerms?.nearDate), farDate: str(d.fxTerms?.farDate), nearRate: str(d.fxTerms?.nearRate), farRate: str(d.fxTerms?.farRate),
    fraPeriod: str(d.fraTerms?.period), fraSettlement: str(d.fraTerms?.settlementConvention) || 'ADVANCE_DISCOUNTED',
    principalAccountId: str(d.depositLoanTerms?.principalAccountId), interestAccountId: str(d.depositLoanTerms?.interestAccountId),
    earlyWithdrawalAllowed: d.depositLoanTerms?.earlyWithdrawal?.allowed ?? false,
    earlyWithdrawalNoticeDays: str(d.depositLoanTerms?.earlyWithdrawal?.noticeDays), earlyWithdrawalPenaltyBp: str(d.depositLoanTerms?.earlyWithdrawal?.penaltyBp),
    earlyWithdrawalNote: str(d.depositLoanTerms?.earlyWithdrawal?.note),
    collIsin: str(d.collateral?.securityIsin), collNominal: str(d.collateral?.nominal), collDirtyPrice: str(d.collateral?.dirtyPrice),
    collHaircut: str(d.collateral?.haircutPercent), collSubstitution: d.collateral?.substitutionAllowed ?? false, collMarginCall: str(d.collateral?.marginCallThreshold),
    cashAccountId: str(d.settlementAccounts?.cashAccountId), securitiesAccountId: str(d.settlementAccounts?.securitiesAccountId),
    documents: (d.documents ?? []).map(x => ({ documentId: x.documentId, type: x.type })),
    fees: (d.fees ?? []).map(x => ({ type: x.type, amount: str(x.amount), currency: x.currency, paymentDate: x.paymentDate })),
    linkedDeals: (d.linkedDeals ?? []).map(x => ({ dealId: x.dealId, relation: x.relation })), originalDealId: str(d.originalDealId),
    centralBankCodes: (reg?.centralBankCodes ?? []).join(', '), lcrCategory: str(reg?.lcrCategory), nsfrCategory: str(reg?.nsfrCategory),
    uti: str(reg?.tradeReporting?.uti), counterpartyLei: str(reg?.tradeReporting?.counterpartyLei),
    clearingObligation: reg?.tradeReporting?.clearingObligation == null ? '' : reg.tradeReporting.clearingObligation ? 'true' : 'false',
    collateralisation: str(reg?.tradeReporting?.collateralisation),
    legs: (d.legs ?? []).length ? (d.legs ?? []).map(leg) : f.legs,
  }
}

// ── validation ──────────────────────────────────────────────────────────────────────────────────
export type Errors = Record<string, string>

const isNum = (s: string) => s.trim() !== '' && Number.isFinite(Number(s))
const pos = (s: string) => isNum(s) && Number(s) > 0
const ISIN = /^[A-Z]{2}[A-Z0-9]{9}[0-9]$/
const LEI = /^[A-Z0-9]{18}[0-9]{2}$/
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const csv = (s: string): string[] => s.split(',').map(x => x.trim()).filter(Boolean)

export function parseCustomSchedule(lines: string): { date: string; amount: number }[] | null {
  const out: { date: string; amount: number }[] = []
  for (const raw of lines.split('\n').map(l => l.trim()).filter(Boolean)) {
    const [d, a] = raw.split(/[;,\s]+/)
    if (!d || !isIsoDate(d) || !isNum(a ?? '')) return null
    out.push({ date: d, amount: Number(a) })
  }
  return out
}

export function validateLeg(l: LegForm, i: number, t: T): Errors {
  const e: Errors = {}
  const k = (f: string) => `legs.${i}.${f}`
  if (!pos(l.notional)) e[k('notional')] = t('Jistina musí být kladné číslo.', 'Notional must be a positive number.')
  if (!isIsoDate(l.accrualStart)) e[k('accrualStart')] = t('Zadejte začátek akruálního období.', 'Enter the accrual start date.')
  if (!isIsoDate(l.accrualEnd)) e[k('accrualEnd')] = t('Zadejte konec akruálního období.', 'Enter the accrual end date.')
  else if (isIsoDate(l.accrualStart) && l.accrualEnd <= l.accrualStart) e[k('accrualEnd')] = t('Konec musí následovat po začátku.', 'The end must be after the start.')
  if (l.amortisationStart && !isIsoDate(l.amortisationStart)) e[k('amortisationStart')] = t('Neplatné datum.', 'Invalid date.')
  if (l.amortisation === 'CUSTOM' && parseCustomSchedule(l.amortisationLines) === null) {
    e[k('amortisationLines')] = t('Každý řádek: RRRR-MM-DD;částka.', 'Each line: YYYY-MM-DD;amount.')
  }
  if (!/^\d+$/.test(l.paymentLagBusinessDays.trim() || '0')) e[k('paymentLagBusinessDays')] = t('Celé číslo ≥ 0.', 'A whole number ≥ 0.')
  if (l.rateType === 'FIXED' && !(isNum(l.rate) && Number(l.rate) >= 0)) e[k('rate')] = t('Zadejte pevnou sazbu (≥ 0).', 'Enter the fixed rate (≥ 0).')
  if (l.rateType === 'FLOATING') {
    if (!l.index.trim()) e[k('index')] = t('Zadejte referenční index.', 'Enter the reference index.')
    if (l.spread.trim() && !isNum(l.spread)) e[k('spread')] = t('Neplatné číslo.', 'Invalid number.')
    if (!/^\d+$/.test(l.fixingLagBusinessDays.trim())) e[k('fixingLagBusinessDays')] = t('Celé číslo ≥ 0.', 'A whole number ≥ 0.')
    if (l.rateUntilFirstFixing.trim() && !isNum(l.rateUntilFirstFixing)) e[k('rateUntilFirstFixing')] = t('Neplatné číslo.', 'Invalid number.')
    if (l.cap.trim() && !isNum(l.cap)) e[k('cap')] = t('Neplatné číslo.', 'Invalid number.')
    if (l.floor.trim() && !isNum(l.floor)) e[k('floor')] = t('Neplatné číslo.', 'Invalid number.')
    if (isNum(l.cap) && isNum(l.floor) && Number(l.cap) < Number(l.floor)) e[k('cap')] = t('Strop nesmí být pod spodní hranicí.', 'The cap may not be below the floor.')
  }
  return e
}

export function validateTicket(f: TicketForm, t: T): Errors {
  const e: Errors = {}
  const p = profileOf(f.product)
  const facility = f.product === 'CNB_DEPOSIT_FACILITY' || f.product === 'CNB_LOMBARD'
  if (!f.counterpartyId && !facility) e.counterpartyId = t('Vyberte protistranu.', 'Select a counterparty.')
  if (p.needsBook && !f.bookId.trim()) e.bookId = t('Portfolio (kniha) je u tohoto produktu povinné.', 'A portfolio (book) is required for this product.')
  if (f.valueDate && !isIsoDate(f.valueDate)) e.valueDate = t('Neplatné datum.', 'Invalid date.')
  else if (f.product === 'FX_SPOT' && f.valueDate && f.valueDate > fxSpotDate(bankToday())) e.valueDate = t('Datum valuty je později než T+2 (forward).', 'The value date is later than T+2 (a forward).')
  else if (!f.valueDate && f.product !== 'FX_SPOT') e.valueDate = t('Zadejte datum valuty.', 'Enter the value date.')
  if (f.maturityDate && !isIsoDate(f.maturityDate)) e.maturityDate = t('Neplatné datum.', 'Invalid date.')
  else if (f.maturityDate && f.valueDate && f.maturityDate <= f.valueDate) e.maturityDate = t('Splatnost musí následovat po datu valuty.', 'Maturity must be after the value date.')
  if (f.validFrom && !isIsoDate(f.validFrom)) e.validFrom = t('Neplatné datum.', 'Invalid date.')
  if (f.validTo && !isIsoDate(f.validTo)) e.validTo = t('Neplatné datum.', 'Invalid date.')
  else if (f.validFrom && f.validTo && f.validTo < f.validFrom) e.validTo = t('Platnost do nesmí předcházet platnosti od.', 'Valid-to may not precede valid-from.')
  if (f.jurisdiction && !/^[A-Za-z]{2}$/.test(f.jurisdiction.trim())) e.jurisdiction = t('Dvoupísmenný kód země.', 'A two-letter country code.')

  if (p.legacy) {
    if (!pos(f.principal)) e.principal = f.product === 'FX_SPOT' ? t('Zadejte cizoměnovou částku.', 'Enter the foreign amount.') : t('Jistina musí být kladné číslo.', 'Principal must be a positive number.')
    const lombard = f.product === 'CNB_LOMBARD'
    if (!isNum(f.rate) || (lombard ? Number(f.rate) <= 0 : Number(f.rate) < 0)) e.rate = t('Zadejte platnou sazbu.', 'Enter a valid rate.')
    if (f.product === 'FX_SPOT' && !((f.buyCurrency === 'CZK') !== (f.sellCurrency === 'CZK'))) {
      e.buyCurrency = t('Právě jedna z měn musí být CZK.', 'Exactly one of the two currencies must be CZK.')
    }
  }
  f.legs.forEach((l, i) => Object.assign(e, validateLeg(l, i, t)))
  if (f.product === 'IRS' && f.legs.length === 2 && f.legs[0].payReceive === f.legs[1].payReceive) {
    e['legs.1.payReceive'] = t('Swap musí mít jednu platící a jednu přijímající nohu.', 'A swap needs one pay leg and one receive leg.')
  }
  if (p.panels.includes('securities')) {
    if (!ISIN.test(f.securityIsin.trim().toUpperCase())) e.securityIsin = t('Neplatné ISIN (12 znaků).', 'Invalid ISIN (12 characters).')
    if (!pos(f.nominal)) e.nominal = t('Nominál musí být kladný.', 'Nominal must be positive.')
    if (!pos(f.priceValue)) e.priceValue = f.priceMode === 'YIELD' ? t('Zadejte výnos.', 'Enter the yield.') : t('Zadejte čistou cenu.', 'Enter the clean price.')
  }
  if (p.panels.includes('fx')) {
    if (f.buyCurrency === f.sellCurrency) e.buyCurrency = t('Měny nákupu a prodeje se musí lišit.', 'Buy and sell currencies must differ.')
    if (!pos(f.fxBuyAmount)) e.fxBuyAmount = t('Zadejte kupovanou částku.', 'Enter the buy amount.')
    if (!pos(f.fxSellAmount)) e.fxSellAmount = t('Zadejte prodávanou částku.', 'Enter the sell amount.')
    if (!pos(f.fxRate)) e.fxRate = t('Zadejte kurz.', 'Enter the rate.')
    if (f.forwardPoints.trim() && !isNum(f.forwardPoints)) e.forwardPoints = t('Neplatné číslo.', 'Invalid number.')
    if (!isIsoDate(f.farDate)) e.farDate = t('Zadejte datum vzdálené nohy / forwardu.', 'Enter the far-leg / forward date.')
    if (f.product === 'FX_SWAP') {
      if (!isIsoDate(f.nearDate)) e.nearDate = t('Zadejte datum blízké nohy.', 'Enter the near-leg date.')
      else if (isIsoDate(f.farDate) && f.farDate <= f.nearDate) e.farDate = t('Vzdálená noha musí následovat po blízké.', 'The far leg must be after the near leg.')
      if (!pos(f.nearRate)) e.nearRate = t('Zadejte kurz blízké nohy.', 'Enter the near-leg rate.')
      if (!pos(f.farRate)) e.farRate = t('Zadejte kurz vzdálené nohy.', 'Enter the far-leg rate.')
    }
  }
  if (p.panels.includes('deposit') && f.earlyWithdrawalAllowed) {
    const intIn = (v: string, max: number) => /^\d+$/.test(v.trim()) && Number(v) <= max
    if (f.earlyWithdrawalNoticeDays.trim() && !intIn(f.earlyWithdrawalNoticeDays, 3660)) e.earlyWithdrawalNoticeDays = t('Výpovědní lhůta 0–3660 dní.', 'Notice 0–3660 days.')
    if (f.earlyWithdrawalPenaltyBp.trim() && !intIn(f.earlyWithdrawalPenaltyBp, 10000)) e.earlyWithdrawalPenaltyBp = t('Sankce 0–10000 bp.', 'Penalty 0–10000 bp.')
  }
  if (p.panels.includes('fra') && !/^\d+\s*[xX×]\s*\d+$/.test(f.fraPeriod.trim())) e.fraPeriod = t('Období ve tvaru 3x6.', 'Period in the form 3x6.')
  if (p.panels.includes('collateral')) {
    if (!ISIN.test(f.collIsin.trim().toUpperCase())) e.collIsin = t('Neplatné ISIN zajištění.', 'Invalid collateral ISIN.')
    if (!pos(f.collNominal)) e.collNominal = t('Nominál musí být kladný.', 'Nominal must be positive.')
    if (!(isNum(f.collHaircut) && Number(f.collHaircut) >= 0 && Number(f.collHaircut) < 100)) e.collHaircut = t('Haircut 0 až <100 %.', 'Haircut 0 to <100 %.')
    if (f.collDirtyPrice.trim() && !pos(f.collDirtyPrice)) e.collDirtyPrice = t('Cena musí být kladná.', 'Price must be positive.')
    if (f.collMarginCall.trim() && !isNum(f.collMarginCall)) e.collMarginCall = t('Neplatné číslo.', 'Invalid number.')
  }
  f.fees.forEach((x, i) => {
    if (!x.type.trim()) e[`fees.${i}.type`] = t('Zadejte typ poplatku.', 'Enter the fee type.')
    if (!pos(x.amount)) e[`fees.${i}.amount`] = t('Částka musí být kladná.', 'Amount must be positive.')
    if (!isIsoDate(x.paymentDate)) e[`fees.${i}.paymentDate`] = t('Zadejte datum platby.', 'Enter the payment date.')
  })
  f.documents.forEach((x, i) => { if (!x.documentId.trim()) e[`documents.${i}.documentId`] = t('Zadejte ID dokumentu.', 'Enter the document id.') })
  f.linkedDeals.forEach((x, i) => { if (!UUID.test(x.dealId.trim())) e[`linkedDeals.${i}.dealId`] = t('Zadejte ID obchodu (UUID).', 'Enter the deal id (UUID).') })
  if (f.originalDealId.trim() && !UUID.test(f.originalDealId.trim())) e.originalDealId = t('Neplatné ID obchodu (UUID).', 'Invalid deal id (UUID).')
  if (f.counterpartyLei.trim() && !LEI.test(f.counterpartyLei.trim().toUpperCase())) e.counterpartyLei = t('LEI má 20 znaků.', 'An LEI has 20 characters.')
  if (f.uti.trim().length > 52) e.uti = t('UTI má nejvýše 52 znaků.', 'A UTI has at most 52 characters.')
  return e
}

// ── request building ────────────────────────────────────────────────────────────────────────────
const numOrNull = (s: string): number | null => (isNum(s) ? Number(s) : null)
const orUndef = <T,>(v: T | '' | null): T | undefined => (v === '' || v === null ? undefined : v)

export function buildLeg(l: LegForm) {
  const schedule = l.amortisation === 'CUSTOM' ? parseCustomSchedule(l.amortisationLines) ?? [] : undefined
  return {
    payReceive: l.payReceive,
    currency: l.currency,
    notional: Number(l.notional),
    amortisation: { type: l.amortisation, ...(schedule ? { schedule } : {}) },
    amortisationStart: l.amortisationStart || null,
    accrualStart: l.accrualStart,
    accrualEnd: l.accrualEnd,
    accrualFrequency: l.accrualFrequency,
    paymentFrequency: l.paymentFrequency,
    stub: l.stub,
    adjustPaymentDate: l.adjustPaymentDate,
    adjustAccrualPeriod: l.adjustAccrualPeriod,
    paymentLagBusinessDays: Number(l.paymentLagBusinessDays || 0),
    paymentCalendars: csv(l.paymentCalendars),
    dayCount: l.dayCount,
    rate: l.rateType === 'FIXED'
      ? { type: 'FIXED', rate: Number(l.rate) }
      : l.rateType === 'NONE'
        ? { type: 'NONE' }
        : {
            type: 'FLOATING', index: l.index.trim(), spread: numOrNull(l.spread) ?? 0,
            fixingLagBusinessDays: Number(l.fixingLagBusinessDays), resetFrequency: l.resetFrequency,
            cap: numOrNull(l.cap), floor: numOrNull(l.floor),
            capFloorAppliesTo: l.cap.trim() || l.floor.trim() ? l.capFloorAppliesTo : null,
            rateUntilFirstFixing: numOrNull(l.rateUntilFirstFixing),
          },
    initialNotionalExchange: l.initialNotionalExchange,
    finalNotionalExchange: l.finalNotionalExchange,
  }
}

/** Two bullet legs from the FX terms (the contract requires legs for FX forward and swap). */
export function fxLegs(f: TicketForm): ReturnType<typeof buildLeg>[] {
  const swap = f.product === 'FX_SWAP'
  const start = swap ? f.nearDate : f.valueDate
  const base = (payReceive: 'PAY' | 'RECEIVE', currency: string, amount: string): LegForm => ({
    ...emptyLeg(payReceive, currency), notional: amount, accrualStart: start, accrualEnd: f.farDate, rateType: 'NONE',
    accrualFrequency: 'ONCE', paymentFrequency: 'ONCE', initialNotionalExchange: swap, finalNotionalExchange: true,
  })
  return [buildLeg(base('PAY', f.sellCurrency, f.fxSellAmount)), buildLeg(base('RECEIVE', f.buyCurrency, f.fxBuyAmount))]
}

export function legsFor(f: TicketForm) {
  const p = profileOf(f.product)
  if (p.panels.includes('fx')) return fxLegs(f)
  return f.legs.map(buildLeg)
}

/** The DraftDealRequest. Legacy products keep their flat shape; ADR-0344 fields are additive and
 * only sent when set, so an older service never sees a field it does not know. */
export function buildRequest(f: TicketForm): Record<string, unknown> {
  const p = profileOf(f.product)
  const tradeDate = f.tradeDateTime ? f.tradeDateTime.slice(0, 10) : undefined
  const reg = {
    tradeReporting: {
      uti: orUndef(f.uti.trim()) ?? null, counterpartyLei: orUndef(f.counterpartyLei.trim().toUpperCase()) ?? null,
      clearingObligation: f.clearingObligation === '' ? null : f.clearingObligation === 'true',
      collateralisation: orUndef(f.collateralisation) ?? null,
    },
    centralBankCodes: csv(f.centralBankCodes), lcrCategory: orUndef(f.lcrCategory.trim()) ?? null, nsfrCategory: orUndef(f.nsfrCategory.trim()) ?? null,
  }
  const hasReg = Object.values(reg.tradeReporting).some(v => v !== null) || reg.centralBankCodes.length > 0 || reg.lcrCategory !== null || reg.nsfrCategory !== null
  const additive: Record<string, unknown> = {
    ...(f.kind !== 'ACTUAL' ? { kind: f.kind } : {}),
    ...(f.bookId.trim() ? { bookId: f.bookId.trim() } : {}),
    ...(f.legalEntityId.trim() ? { legalEntityId: f.legalEntityId.trim() } : {}),
    ...(f.counterpartyReference.trim() ? { counterpartyReference: f.counterpartyReference.trim() } : {}),
    ...(f.tradeDateTime ? { tradeDateTime: new Date(f.tradeDateTime).toISOString(), tradeDate } : {}),
    ...(f.validFrom ? { validFrom: f.validFrom } : {}),
    ...(f.validTo ? { validTo: f.validTo } : {}),
    ...(f.jurisdiction.trim() ? { jurisdiction: f.jurisdiction.trim().toUpperCase() } : {}),
    ...(csv(f.calendars).length ? { calendars: csv(f.calendars) } : {}),
    ...(!p.legacy || f.fixedConvention ? { businessDayConvention: f.businessDayConvention, rollDirection: f.rollDirection } : {}),
    ...(f.fixedConvention ? { fixedConvention: true } : {}),
    ...(f.notionalExchange ? { notionalExchange: true } : {}),
    ...(!f.singleCurrency ? { singleCurrency: false } : {}),
    ...(f.note.trim() ? { note: f.note.trim() } : {}),
    ...(f.channel && !p.legacy ? { channel: f.channel } : {}),
    ...(f.cashAccountId.trim() || f.securitiesAccountId.trim()
      ? { settlementAccounts: { cashAccountId: f.cashAccountId.trim() || undefined, securitiesAccountId: f.securitiesAccountId.trim() || null } } : {}),
    ...(f.documents.length ? { documents: f.documents.map(d => ({ documentId: d.documentId.trim(), type: d.type })) } : {}),
    ...(f.fees.length ? { fees: f.fees.map(x => ({ type: x.type.trim(), amount: Number(x.amount), currency: x.currency, paymentDate: x.paymentDate })) } : {}),
    ...(f.linkedDeals.length ? { linkedDeals: f.linkedDeals.map(x => ({ dealId: x.dealId.trim(), relation: x.relation })) } : {}),
    ...(f.originalDealId.trim() ? { originalDealId: f.originalDealId.trim() } : {}),
    ...(hasReg ? { regulatory: reg } : {}),
  }
  const common = {
    product: f.product,
    counterpartyId: f.counterpartyId,
    ...(f.rationale.trim() ? { rationale: f.rationale.trim() } : {}),
    ...additive,
  }
  if (p.legacy) {
    return f.product === 'FX_SPOT'
      ? { ...common, buyCurrency: f.buyCurrency, sellCurrency: f.sellCurrency, principal: Number(f.principal), rate: Number(f.rate), ...(f.valueDate ? { valueDate: f.valueDate } : {}) }
      : {
          ...common, currency: f.currency, principal: Number(f.principal), rate: Number(f.rate), valueDate: f.valueDate,
          ...(f.maturityDate && f.product !== 'CNB_DEPOSIT_FACILITY' && f.product !== 'CNB_LOMBARD' ? { maturityDate: f.maturityDate } : {}),
        }
  }
  const first = f.legs[0]
  const out: Record<string, unknown> = {
    ...common,
    currency: first?.currency ?? f.currency,
    principal: first ? Number(first.notional) : Number(f.nominal || f.fxBuyAmount || 0),
    rate: first && first.rateType === 'FIXED' ? Number(first.rate) : 0,
    valueDate: f.valueDate,
    effectiveDate: f.valueDate,
    ...(f.maturityDate ? { maturityDate: f.maturityDate } : {}),
  }
  if (p.sendLegs || f.legs.length) out.legs = legsFor(f)
  if (p.panels.includes('securities')) {
    out.securityIsin = f.securityIsin.trim().toUpperCase()
    out.nominal = Number(f.nominal)
    out[f.priceMode === 'YIELD' ? 'yield' : 'cleanPrice'] = Number(f.priceValue)
    out.securityTerms = {
      securityIsin: f.securityIsin.trim().toUpperCase(), nominal: Number(f.nominal),
      cleanPrice: f.priceMode === 'CLEAN_PRICE' ? Number(f.priceValue) : null, yield: f.priceMode === 'YIELD' ? Number(f.priceValue) : null,
      accruedInterest: numOrNull(f.accruedInterest), settlementPlace: f.settlementPlace.trim() || null, custodyAccountId: f.custodyAccountId.trim() || null,
    }
  }
  if (p.panels.includes('fx')) {
    out.currency = f.buyCurrency
    out.principal = Number(f.fxBuyAmount)
    out.rate = Number(f.fxRate)
    out.buyCurrency = f.buyCurrency
    out.sellCurrency = f.sellCurrency
    out.fxTerms = {
      buyCurrency: f.buyCurrency, buyAmount: Number(f.fxBuyAmount), sellCurrency: f.sellCurrency, sellAmount: Number(f.fxSellAmount),
      rate: Number(f.fxRate), forwardPoints: numOrNull(f.forwardPoints), nearDate: f.nearDate || null, farDate: f.farDate || null,
      nearRate: numOrNull(f.nearRate), farRate: numOrNull(f.farRate),
    }
    out.maturityDate = f.farDate
  }
  if (p.panels.includes('fra')) out.fraTerms = { period: f.fraPeriod.replace(/\s|×/g, m => (m === '×' ? 'x' : '')).toLowerCase(), settlementConvention: f.fraSettlement }
  if (p.panels.includes('collateral')) {
    out.collateral = {
      securityIsin: f.collIsin.trim().toUpperCase(), nominal: Number(f.collNominal), haircutPercent: Number(f.collHaircut),
      dirtyPrice: numOrNull(f.collDirtyPrice), substitutionAllowed: f.collSubstitution, marginCallThreshold: numOrNull(f.collMarginCall),
    }
  }
  if (p.panels.includes('deposit')) {
    // Typed deposit / loan terms (no longer carried in the agent `inputs`).
    const early = f.earlyWithdrawalAllowed
      ? { allowed: true, noticeDays: f.earlyWithdrawalNoticeDays.trim() ? Number(f.earlyWithdrawalNoticeDays) : null, penaltyBp: f.earlyWithdrawalPenaltyBp.trim() ? Number(f.earlyWithdrawalPenaltyBp) : null, note: f.earlyWithdrawalNote.trim() || null }
      : (f.earlyWithdrawalNote.trim() ? { allowed: false, note: f.earlyWithdrawalNote.trim() } : null)
    const terms = { principalAccountId: f.principalAccountId.trim() || null, interestAccountId: f.interestAccountId.trim() || null, earlyWithdrawal: early }
    if (terms.principalAccountId || terms.interestAccountId || terms.earlyWithdrawal) out.depositLoanTerms = terms
  }
  if (!out.maturityDate) delete out.maturityDate
  return out
}

// ── mapping a refusal back onto fields ──────────────────────────────────────────────────────────
/** "calendar:CERTIS" → the field that holds a calendar. */
export function fieldForMissingReference(ref: string): string {
  const kind = ref.split(':')[0].toLowerCase()
  switch (kind) {
    case 'book': return 'bookId'
    case 'calendar': return 'calendars'
    case 'index': return 'legs.index'
    case 'security': return 'securityIsin'
    case 'ssi': return 'cashAccountId'
    case 'counterparty': return 'counterpartyId'
    default: return '_form'
  }
}

export function breachFieldErrors(r: Refusal, t: T): Errors {
  const e: Errors = {}
  for (const b of r.breaches) {
    const text = (() => {
      switch (b.rule) {
        case 'PRODUCT_NOT_PERMITTED': return t('Produkt není pro banku povolen.', 'This product is not permitted.')
        case 'CURRENCY_NOT_PERMITTED': return t(`Měna není u produktu povolena (povoleno: ${b.limit}, zadáno: ${b.actual}).`, `Currency not permitted for the product (allowed: ${b.limit}, entered: ${b.actual}).`)
        case 'MAX_PRINCIPAL': return t(`Jistina překračuje produktový limit ${b.limit} (zadáno ${b.actual}).`, `Principal exceeds the product limit ${b.limit} (entered ${b.actual}).`)
        case 'MAX_TENOR': return t(`Splatnost překračuje maximální dobu ${b.limit} dní (zadáno ${b.actual}).`, `Tenor exceeds the maximum ${b.limit} days (entered ${b.actual}).`)
        default: return `${b.rule}: ${b.limit} / ${b.actual}`
      }
    })()
    const field = b.rule === 'CURRENCY_NOT_PERMITTED' ? 'currency' : b.rule === 'MAX_TENOR' ? 'maturityDate' : b.rule === 'MAX_PRINCIPAL' ? 'principal' : 'product'
    e[field] = e[field] ? `${e[field]} ${text}` : text
  }
  return e
}

export function referenceFieldErrors(r: Refusal, t: T): Errors {
  const e: Errors = {}
  for (const ref of r.missingReferences) {
    const field = fieldForMissingReference(ref)
    const msg = t(`Odkazovaná data „${ref}“ nejsou k datu obchodu platná.`, `Referenced data “${ref}” is not effective on the trade date.`)
    e[field] = e[field] ? `${e[field]} ${msg}` : msg
  }
  return e
}

/** All field-level errors a refusal carries; whatever has no field lands on `_form`. */
export function refusalErrors(r: Refusal, t: T): Errors {
  const e: Errors = {}
  if (r.code === 'REFERENCE_NOT_EFFECTIVE') Object.assign(e, referenceFieldErrors(r, t))
  if (r.code === 'PRODUCT_LIMIT_BREACHED') Object.assign(e, breachFieldErrors(r, t))
  return e
}

/** An error keyed `legs.index` applies to every leg's index control. */
export function errorFor(errors: Errors, key: string): string | null {
  if (errors[key]) return errors[key]
  const m = /^legs\.\d+\.(.+)$/.exec(key)
  return m && errors[`legs.${m[1]}`] ? errors[`legs.${m[1]}`] : null
}
