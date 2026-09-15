// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('@/components/agent/AgentInsightsPanel', () => ({ AgentInsightsPanel: () => null }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

import FinOpsPage from '@/app/finops/page'

const lifecycle = {
  currentVersion: '1.31', currentTier: 'standard', daysToStandardEnd: 200, runwayStatus: 'ok',
  standardSupportEnds: '2027-01-01', extendedSupportEnds: '2028-01-01', monthlyEstimate: 100,
  annualEstimate: 1200, monthlySavingsVsExtended: 10, annualSavingsVsExtended: 120,
  minRunwayDays: 180, versions: [], components: [], dataSource: 'embedded', lastRefreshed: '2026-09-15', adrRef: 'ADR-0054',
}
const domains = ['Platform', 'Governance', 'Security', 'Observability', 'FinOps', 'Tax']
const costs = {
  available: true, currency: 'USD', periodStart: '2026-08-15', periodEnd: '2026-09-15', total: 210,
  services: domains.map((domain, index) => ({ name: `${domain.toLowerCase()}-service`, domain, amount: (index + 1) * 10 })),
  daily: [], collectedAt: '2026-09-15T00:00:00Z', source: 'test',
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/lifecycle')) return new Response(JSON.stringify(lifecycle), { status: 200 })
    if (url.endsWith('/costs')) return new Response(JSON.stringify(costs), { status: 200 })
    if (url.endsWith('/anomalies')) return new Response(JSON.stringify({ anomalies: [] }), { status: 200 })
    return new Response('{}', { status: 503 })
  }))
})

describe('FinOps domain spend rendering', () => {
  it('loads after mount and renders every labelled domain with its adaptive colour', async () => {
    render(<FinOpsPage />)

    const section = await screen.findByRole('region', { name: 'By domain (process / business view)' })
    for (const domain of domains) {
      const label = within(section).getByText(domain)
      expect(label).toHaveStyle({ color: `var(--finops-domain-${domain.toLowerCase()})` })
    }
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(6))
  })
})
