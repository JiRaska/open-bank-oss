// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The reports menu (ADR-0344 catalogue): which service answers each report and with which
// parameters. The risk reports are read from risk-engine through the same BFF relay as the
// balance-sheet pages; payment orders and accounting come from treasury-service.
type T = (cs: string, en: string) => string

export type ReportId =
  | 'payment-orders' | 'cash-flow' | 'liquidity' | 'irrbb' | 'limits' | 'fx-position'
  | 'sensitivity' | 'var' | 'valuation' | 'accounting'

export type ReportSpec = {
  id: ReportId
  cs: string
  en: string
  source: 'risk' | 'treasury'
  /** Needs a risk snapshot (run) to be chosen. */
  snapshot: boolean
  /** Honours the portfolio-node selector. */
  book: boolean
  path: (snapshotId: string) => string
  extra?: ('confidence' | 'horizon' | 'bucketSet')[]
  /** Provenance when the response carries none. */
  provenance?: 'synthetic' | 'intraday' | 'regulatory'
}

export const REPORTS: ReportSpec[] = [
  { id: 'payment-orders', cs: 'Platební příkazy', en: 'Payment orders', source: 'treasury', snapshot: false, book: false, path: () => '/payments', provenance: 'intraday' },
  { id: 'cash-flow', cs: 'Peněžní toky', en: 'Cash flow', source: 'risk', snapshot: true, book: false, path: id => `/snapshots/${id}/cash-flows` },
  { id: 'liquidity', cs: 'Analýza likvidity', en: 'Liquidity analysis', source: 'risk', snapshot: true, book: false, path: id => `/snapshots/${id}/liquidity` },
  { id: 'irrbb', cs: 'Úrokové riziko', en: 'Interest-rate risk', source: 'risk', snapshot: true, book: false, path: id => `/snapshots/${id}/irrbb` },
  { id: 'limits', cs: 'Limity a čerpání', en: 'Limits and utilisation', source: 'risk', snapshot: true, book: false, path: id => `/snapshots/${id}/limits` },
  { id: 'fx-position', cs: 'FX pozice', en: 'FX position', source: 'risk', snapshot: true, book: true, path: id => `/snapshots/${id}/fx-position` },
  { id: 'sensitivity', cs: 'Citlivost (PV01, DV01 po pásmech)', en: 'Sensitivity (PV01, DV01 buckets)', source: 'risk', snapshot: true, book: true, path: id => `/snapshots/${id}/sensitivity`, extra: ['bucketSet'] },
  { id: 'var', cs: 'Value at Risk', en: 'Value at risk', source: 'risk', snapshot: true, book: true, path: id => `/snapshots/${id}/var`, extra: ['confidence', 'horizon'] },
  { id: 'valuation', cs: 'Ocenění', en: 'Valuation', source: 'risk', snapshot: true, book: true, path: id => `/snapshots/${id}/valuation` },
  { id: 'accounting', cs: 'Účetnictví (pravidla zaúčtování)', en: 'Accounting (posting rules)', source: 'treasury', snapshot: false, book: false, path: () => '/posting-rules', provenance: 'regulatory' },
]

export const reportName = (r: ReportSpec, t: T) => t(r.cs, r.en)

export type Provenance = 'synthetic' | 'intraday' | 'regulatory'

/** Normalise whatever a response calls its provenance (`production` ⇒ regulatory). */
export function provenanceOf(raw: unknown, fallback?: Provenance): Provenance | null {
  if (typeof raw === 'string') {
    const v = raw.toLowerCase()
    if (v === 'synthetic' || v === 'intraday' || v === 'regulatory') return v
    if (v === 'production') return 'regulatory'
  }
  return fallback ?? null
}

/** `header.provenance` / `provenance` of a report response, whichever it carries. */
export function responseProvenance(data: unknown, fallback?: Provenance): Provenance | null {
  if (data && typeof data === 'object') {
    const d = data as Record<string, unknown>
    const h = d.header && typeof d.header === 'object' ? (d.header as Record<string, unknown>) : null
    return provenanceOf(h?.provenance ?? d.provenance, fallback)
  }
  return fallback ?? null
}

/** A data gap the service reported: never rendered as a figure. */
export function dataGapOf(data: unknown): string | null {
  if (data && typeof data === 'object') {
    const g = (data as Record<string, unknown>).dataGap
    return typeof g === 'string' && g.trim() ? g : null
  }
  return null
}
