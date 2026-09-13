// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_CREDIT_RISK'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  const Nil = () => null
  return {
    ResponsiveContainer: Pass, BarChart: Pass, Bar: Pass, ScatterChart: Pass, Scatter: Nil,
    PieChart: Pass, Pie: Pass, XAxis: Nil, YAxis: Nil, ZAxis: Nil, CartesianGrid: Nil,
    Tooltip: Nil, Legend: Nil, Cell: Nil, ReferenceLine: Nil,
  }
})

import CreditRiskPage from '@/app/lending/risk/page'

const POLICY = {
  asOf: '2026-09-05', source: 'StarterCreditPolicy', codeSeeded: true,
  tables: [{
    kind: 'AFFORDABILITY', name: 'starter-affordability', version: 1, effectiveFrom: '1970-01-01', effectiveTo: null,
    rules: [{ id: 'starter-af-dsti', attribute: 'DSTI', operator: 'LTE', threshold: 0.45, values: [], band: null, detail: 'debt service to income above 45%' }],
  }],
}

const DECISION = {
  applicationId: 'app-1', partyId: 'party-1', status: 'FOUR_EYES', createdAt: '2026-09-01T10:00:00Z',
  requestedAmount: 120000, currency: 'CZK', termPeriods: 12, nominalAnnualRate: 0.08,
  jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', productKind: 'UNSECURED', packVersion: 1,
  engineOutcome: 'APPROVE', priceBand: 'PRIME', reasons: [], matchedRuleIds: ['starter-af-dsti'],
  policyVersions: { AFFORDABILITY: 1 }, inputSnapshotHash: 'h1', decidedEngineAt: '2026-09-01T10:00:05Z',
  affordability: { dsti: 0.17, dti: 2, dstiIncludingExistingDebt: 0.27 },
  verifiedIncomeMonthly: 60000, existingDebtServiceMonthly: 6000, ageYears: 35, residency: 'CZ',
  employmentTenureMonths: 48, humanDecidedBy: null, humanDecisionReason: null, humanDecidedAt: null,
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

function route(url: string) {
  if (url.includes('/risk/decisions/summary')) return json([{ engineOutcome: 'APPROVE', priceBand: 'PRIME', count: 300 }, { engineOutcome: 'REFER', priceBand: null, count: 100 }])
  if (url.includes('/risk/decisions')) return json([DECISION])
  if (url.includes('/risk/portfolio')) return json([])
  if (url.includes('/risk/policy')) return json(POLICY)
  return json([], 404)
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
beforeEach(() => { vi.stubGlobal('fetch', vi.fn(async (u: string) => route(String(u)))) })

const tiles = () => Array.from(document.querySelectorAll('.stat-value')).map(n => n.textContent ?? '')

describe('credit-risk console', () => {
  it('reports the approve rate from the DB summary, not from the capped list', async () => {
    render(<LanguageProvider><CreditRiskPage /></LanguageProvider>)
    // 300 of 400 book-wide = 75%. The loaded list holds ONE row; a page deriving from it would say 100%.
    await waitFor(() => expect(tiles().join(' ')).toMatch(/75/))
    expect(tiles().join(' ')).not.toMatch(/\b100 %/)
  })

  it('states the policy provenance and the placeholder caveats', async () => {
    render(<LanguageProvider><CreditRiskPage /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/code-seeded/i)).toBeInTheDocument())
    expect(screen.getByText(/Bureau port is a no-op/i)).toBeInTheDocument()
    expect(screen.getByText(/PD\/LGD are placeholder constants/i)).toBeInTheDocument()
  })

  it('takes the DSTI threshold from the policy response rather than a constant', async () => {
    render(<LanguageProvider><CreditRiskPage /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/DSTI ≤ 0\.45/)).toBeInTheDocument())
  })

  it('does not claim a zero book while the endpoints are failing', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'down' }, 503)))
    render(<LanguageProvider><CreditRiskPage /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/did not answer/i)).toBeInTheDocument())
    expect(tiles()).not.toContain('0')
    expect(tiles().filter(v => v === '—').length).toBeGreaterThan(0)
  })
})
