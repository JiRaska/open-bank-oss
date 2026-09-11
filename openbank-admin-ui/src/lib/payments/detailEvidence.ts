// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export type PaymentRail = 'SEPA' | 'DOMESTIC'

export interface PaymentDetailEvidence {
  id: string
  type: PaymentRail
  status: string
  amount: number
  currency: string
  debtorAccountId?: string
  debtorIban?: string
  debtorAccountNumber?: string
  debtorBankCode?: string
  debtorName?: string
  creditorIban?: string
  creditorAccountNumber?: string
  creditorBankCode?: string
  creditorName?: string
  creditorBic?: string
  remittanceInfo?: string
  messageForPayee?: string
  endToEndId?: string
  variableSymbol?: string
  specificSymbol?: string
  constantSymbol?: string
  priority?: string
  transferScope?: string
  rejectReason?: string
  rejectDetail?: string
  submittedAt?: string
  completedAt?: string
  settledAt?: string
  createdAt: string
  updatedAt?: string
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const CURRENCY = /^[A-Z]{3}$/
const SEPA_STATUSES = new Set(['RECEIVED', 'VALIDATED', 'PROCESSING', 'COMPLETED', 'REJECTED', 'RETURNED', 'CANCELLED'])
const DOMESTIC_STATUSES = new Set(['RECEIVED', 'VALIDATED', 'SENT_TO_CLEARING', 'SETTLED', 'REJECTED', 'RETURNED', 'CANCELLED'])

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const string = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim().length > 0 ? value : undefined

const optionalString = (value: unknown): string | undefined | null =>
  value == null ? undefined : string(value) ?? null

const instant = (value: unknown): string | undefined | null => {
  const candidate = optionalString(value)
  return candidate == null || !Number.isNaN(Date.parse(candidate)) ? candidate : null
}

const amount = (value: unknown): number | undefined => {
  const candidate = typeof value === 'number'
    ? value
    : typeof value === 'string' && value.trim() !== ''
      ? Number(value)
      : Number.NaN
  return Number.isFinite(candidate) && candidate > 0 ? candidate : undefined
}

/** Convert an untrusted service response or session handoff into display-safe evidence. */
export function parsePaymentDetailEvidence(
  raw: unknown,
  expectedId: string,
  rail: PaymentRail,
): PaymentDetailEvidence | null {
  if (!isRecord(raw) || !UUID.test(expectedId) || raw.id !== expectedId) return null

  const status = string(raw.status)
  const parsedAmount = amount(raw.amount)
  const currency = string(raw.currency) ?? string(raw.currencyCode)
  const createdAt = instant(raw.createdAt)
  const statuses = rail === 'SEPA' ? SEPA_STATUSES : DOMESTIC_STATUSES
  if (!status || !statuses.has(status) || parsedAmount == null || !currency || !CURRENCY.test(currency) || !createdAt) return null

  const evidence: PaymentDetailEvidence = {
    id: expectedId,
    type: rail,
    status,
    amount: parsedAmount,
    currency,
    createdAt,
  }

  const copyString = (key: keyof PaymentDetailEvidence, value: unknown): boolean => {
    const candidate = optionalString(value)
    if (candidate === null) return false
    if (candidate !== undefined) Object.assign(evidence, { [key]: candidate })
    return true
  }
  const copyInstant = (key: keyof PaymentDetailEvidence, value: unknown): boolean => {
    const candidate = instant(value)
    if (candidate === null) return false
    if (candidate !== undefined) Object.assign(evidence, { [key]: candidate })
    return true
  }

  const commonStrings: Array<[keyof PaymentDetailEvidence, unknown]> = [
    ['debtorAccountId', raw.debtorAccountId], ['creditorName', raw.creditorName],
    ['endToEndId', raw.endToEndId], ['rejectReason', raw.rejectReason], ['rejectDetail', raw.rejectDetail],
  ]
  const commonInstants: Array<[keyof PaymentDetailEvidence, unknown]> = [
    ['submittedAt', raw.submittedAt], ['updatedAt', raw.updatedAt],
  ]
  if (!commonStrings.every(([key, value]) => copyString(key, value))) return null
  if (!commonInstants.every(([key, value]) => copyInstant(key, value))) return null

  if (rail === 'SEPA') {
    const strings: Array<[keyof PaymentDetailEvidence, unknown]> = [
      ['debtorIban', raw.debtorIban], ['debtorName', raw.debtorName], ['creditorIban', raw.creditorIban],
      ['creditorBic', raw.creditorBic], ['remittanceInfo', raw.remittanceInfo],
    ]
    if (!strings.every(([key, value]) => copyString(key, value)) || !copyInstant('completedAt', raw.completedAt)) return null
  } else {
    const strings: Array<[keyof PaymentDetailEvidence, unknown]> = [
      ['debtorAccountNumber', raw.debtorAccountNumber], ['debtorBankCode', raw.debtorBankCode],
      ['debtorName', raw.debtorName], ['creditorAccountNumber', raw.creditorAccountNumber],
      ['creditorBankCode', raw.creditorBankCode], ['messageForPayee', raw.messageForPayee],
      ['variableSymbol', raw.variableSymbol], ['specificSymbol', raw.specificSymbol],
      ['constantSymbol', raw.constantSymbol], ['priority', raw.priority], ['transferScope', raw.transferScope],
    ]
    if (!strings.every(([key, value]) => copyString(key, value)) || !copyInstant('settledAt', raw.settledAt)) return null
  }

  return evidence
}
