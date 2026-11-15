// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The movement an AML case is about, read from the service that owns it.
//
// A case opened by a payment rail stores THAT rail's payment (or FX conversion) id in
// `transactionId`, not a transaction-service id — so looking it up in transaction-service
// answered 404 for every rail-opened case (seen live 2026-11-14). aml-service records which
// service opened the case (`movementService`, taken from the authenticated caller); this maps it
// to that service's read endpoint and projects the four response shapes onto one view.
// Cases created before that field existed carry no source: those are tried against each rail
// in turn, transaction-service first, and the first service that knows the id wins.

import { svcUrl } from '@/lib/services/bff'

export interface SourcePayment {
  /** The k8s service the movement was read from, e.g. `domestic-payment`. */
  service: string
  kind: 'domestic' | 'sepa' | 'sepa-instant' | 'fx' | 'transaction'
  status: string | null
  amount: string | null
  currency: string | null
  /** FX only: what the customer received. */
  counterAmount: string | null
  counterCurrency: string | null
  counterparty: string | null
  reference: string | null
  at: string | null
}

interface Source { kind: SourcePayment['kind']; path: (id: string) => string }

/**
 * How each movement-owning service is read: its endpoint and response shape. Keyed by the k8s
 * service name, which is the module name without its `openbank-` prefix — the form aml-service
 * records in `movementService` — so the key is derived, not a second hand-kept list of the fleet.
 */
export const SOURCES: Record<string, Source> = {
  'domestic-payment': { kind: 'domestic', path: id => `/api/v1/domestic-payments/${id}` },
  'sepa-payment': { kind: 'sepa', path: id => `/api/v1/sepa-payments/${id}` },
  'sepa-instant': { kind: 'sepa-instant', path: id => `/api/v1/sepa-instant/${id}` },
  'fx-service': { kind: 'fx', path: id => `/api/v1/fx/conversions/${id}` },
  'transaction-service': { kind: 'transaction', path: id => `/api/v1/transactions/${id}` },
}

const MODULE_PREFIX = 'openbank-'

/** `openbank-domestic-payment` → `domestic-payment`; null when the value names no module. */
export function k8sServiceOf(movementService: string): string | null {
  return movementService.startsWith(MODULE_PREFIX) ? movementService.slice(MODULE_PREFIX.length) : null
}

/** Lookup order for a case with no recorded source. */
export const LEGACY_ORDER = ['transaction-service', 'domestic-payment', 'sepa-instant', 'sepa-payment', 'fx-service'] as const

const s = (v: unknown): string | null => (typeof v === 'string' && v.trim() ? v : typeof v === 'number' ? String(v) : null)

export function projectSourcePayment(service: string, raw: unknown): SourcePayment | null {
  const source = SOURCES[service]
  if (!source || !raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const base = { service, kind: source.kind, status: s(o.status), counterAmount: null, counterCurrency: null }
  switch (source.kind) {
    case 'domestic': return { ...base, amount: s(o.amount), currency: s(o.currencyCode),
      counterparty: [s(o.creditorAccountNumber), s(o.creditorBankCode)].filter(Boolean).join('/') || null,
      reference: s(o.variableSymbol) ? `VS ${s(o.variableSymbol)}` : null, at: s(o.createdAt) }
    case 'sepa': return { ...base, amount: s(o.amount), currency: s(o.currencyCode),
      counterparty: [s(o.creditorName), s(o.creditorIban)].filter(Boolean).join(' · ') || null,
      reference: s(o.endToEndId), at: s(o.createdAt) }
    case 'sepa-instant': return { ...base, amount: s(o.amount), currency: s(o.currency),
      counterparty: s(o.creditorIban), reference: s(o.endToEndId), at: s(o.settledAt) ?? s(o.createdAt) }
    case 'fx': return { ...base, amount: s(o.fromAmount), currency: s(o.fromCurrency),
      counterAmount: s(o.toAmount), counterCurrency: s(o.toCurrency),
      counterparty: s(o.appliedRate) ? `rate ${s(o.appliedRate)}` : null, reference: null, at: s(o.convertedAt) }
    case 'transaction': return { ...base, amount: s(o.amount), currency: s(o.currencyCode),
      counterparty: s(o.description), reference: s(o.referenceNumber), at: s(o.initiatedAt) }
  }
}

/**
 * Resolve the case's movement. `null` = looked everywhere it could be and nothing answered.
 * A known source is asked alone: a 404 there is a real absence, not a reason to guess elsewhere.
 */
export async function fetchSourcePayment(movementService: string | null, id: string, signal?: AbortSignal): Promise<SourcePayment | null> {
  const recorded = movementService ? k8sServiceOf(movementService) : null
  const candidates = movementService ? (recorded && SOURCES[recorded] ? [recorded] : []) : [...LEGACY_ORDER]
  for (const service of candidates) {
    const source = SOURCES[service]
    try {
      const res = await fetch(svcUrl(service, source.path(encodeURIComponent(id))), { cache: 'no-store', signal })
      if (!res.ok) continue
      const projected = projectSourcePayment(service, await res.json())
      if (projected) return projected
    } catch (error) {
      if (signal?.aborted) throw error
    }
  }
  return null
}
