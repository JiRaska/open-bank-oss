// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { isUuid } from '@/lib/party/resolveParty'

export type PaymentSource = 'SEPA' | 'DOMESTIC'

export interface PaymentListItem {
  id: string
  type: PaymentSource
  status: string
  amount: number
  currency: string
  debtorIban?: string
  creditorIban?: string
  creditorAccountNumber?: string
  creditorBankCode?: string
  creditorName?: string
  remittanceInfo?: string
  createdAt: string
}

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function optionalString(value: unknown): value is string | null | undefined {
  return value == null || typeof value === 'string'
}

function amount(value: unknown): number | null {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' ? Number(value) : Number.NaN
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function parsePayment(raw: unknown, type: PaymentSource): PaymentListItem | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const payment = raw as Record<string, unknown>
  const parsedAmount = amount(payment.amount)
  if (
    !nonEmpty(payment.id) || !isUuid(payment.id) || !nonEmpty(payment.status) ||
    parsedAmount === null || !nonEmpty(payment.currency) || !nonEmpty(payment.createdAt) ||
    !Number.isFinite(Date.parse(payment.createdAt)) ||
    !optionalString(payment.creditorName) || !optionalString(payment.remittanceInfo)
  ) return null
  if (type === 'SEPA' && !nonEmpty(payment.creditorIban)) return null
  if (type === 'DOMESTIC' && (!nonEmpty(payment.creditorAccountNumber) || !nonEmpty(payment.creditorBankCode))) return null
  return {
    id: payment.id,
    type,
    status: payment.status,
    amount: parsedAmount,
    currency: payment.currency,
    debtorIban: optionalString(payment.debtorIban) ? payment.debtorIban || undefined : undefined,
    creditorIban: optionalString(payment.creditorIban) ? payment.creditorIban || undefined : undefined,
    creditorAccountNumber: optionalString(payment.creditorAccountNumber) ? payment.creditorAccountNumber || undefined : undefined,
    creditorBankCode: optionalString(payment.creditorBankCode) ? payment.creditorBankCode || undefined : undefined,
    creditorName: payment.creditorName || undefined,
    remittanceInfo: payment.remittanceInfo || undefined,
    createdAt: payment.createdAt,
  }
}

export function parsePaymentListPage(raw: unknown, type: PaymentSource, limit: number): PaymentListItem[] | null {
  if (!Array.isArray(raw) || raw.length > limit) return null
  const items = raw.map(item => parsePayment(item, type))
  if (items.some(item => item === null)) return null
  const validItems = items as PaymentListItem[]
  if (new Set(validItems.map(item => item.id)).size !== validItems.length) return null
  return validItems
}
