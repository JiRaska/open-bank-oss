// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Shared fixtures and a fetch router for the treasury workbench component tests. Not a test file.
import React, { Suspense } from 'react'
import { act, render } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } })

export const notServed = () => new Response('', { status: 404 })

export const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

export const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

export type Call = { url: string; init?: RequestInit }
export const bodyOf = (c: Call) => JSON.parse(String(c.init?.body))
export const keyOf = (c: Call) => new Headers(c.init?.headers).get('idempotency-key')

export const COUNTERPARTIES = [
  { counterpartyId: 'CNB', name: 'Česká národní banka', kind: 'CENTRAL_BANK', synthetic: false, currency: 'CZK', limit: 1e12, exposure: 0, headroom: 1e12 },
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
]

export const deal = (dealId: string, over: Record<string, unknown> = {}) => ({
  dealId, product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25,
  dayCount: 'ACT/360', days: 1, interest: 118.06, tradeDate: '2026-10-19', valueDate: '2026-10-19', maturityDate: '2026-10-20',
  state: 'DRAFT', createdBy: 'dana.dealer', createdByType: 'HUMAN', submittedBy: null, approvedBy: null, rationale: null,
  limitCheck: null, createdAt: '2026-10-19T08:00:00Z', updatedAt: '2026-10-19T08:01:00Z',
  history: [{ from: null, to: 'DRAFT', actor: 'dana.dealer', actorType: 'HUMAN', at: '2026-10-19T08:00:00Z', note: null }],
  journals: [], ...over,
})

export const flow = (n: number, over: Record<string, unknown> = {}) => ({
  flowId: `f-${n}`, legIndex: 0, periodIndex: n, kind: 'INTEREST', direction: 'IN', currency: 'CZK',
  accrualStart: '2026-10-21', accrualEnd: '2027-01-21', fixingDate: '2026-10-19', paymentDate: '2027-01-21', notional: 1000000,
  rate: 4.25, rateSource: 'PROJECTED', amount: 10590.28, state: 'PROJECTED', manual: false, original: null, override: null,
  paymentId: null, journalRefs: [], ...over,
})

export const ledger = (flows: unknown[], over: Record<string, unknown> = {}) => ({
  dealId: 'd-1', dealVersion: 1, scheduleVersion: 1, scheduleHash: 'abcdef0123456789', generatedAt: '2026-10-19T08:02:00Z', flows, ...over,
})
