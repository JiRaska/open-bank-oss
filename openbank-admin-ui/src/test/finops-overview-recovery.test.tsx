// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FinOpsPage from '@/app/finops/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({ AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
const lifecycle = {
  currentVersion: '1.36', currentTier: 'standard', daysToStandardEnd: 365, runwayStatus: 'ok',
  standardSupportEnds: '2027-10-19', extendedSupportEnds: '2028-10-19', monthlyEstimate: 73,
  annualEstimate: 876, monthlySavingsVsExtended: 365, annualSavingsVsExtended: 4380,
  minRunwayDays: 180, versions: [], components: [], dataSource: 'snapshot', lastRefreshed: '2026-10-19', adrRef: 'ADR-0054',
}
const costs = { available: true, currency: 'USD', periodStart: '2026-10-01', periodEnd: '2026-10-19', total: 4321,
  services: [], daily: [], collectedAt: '2026-10-19T10:00:00Z', source: 'snapshot' }
const aiCostsWithMissingMeasurements = {
  available: true,
  collectedAt: '2026-10-19T10:00:00Z',
  totalCostLast7dUsd: null,
  totalCostLast30dUsd: null,
  selfHostedPct: null,
  coverage: {
    source: 'prometheus',
    retentionHours: 12,
    dataFrom: '2026-10-18T22:00:00Z',
    dataTo: '2026-10-19T10:00:00Z',
    lastSuccessfulLoad: null,
    windows: {
      '24h': { requestedHours: 24, availableHours: 12, partial: true },
      '7d': { requestedHours: 168, availableHours: 12, partial: true },
      '30d': { requestedHours: 720, availableHours: 12, partial: true },
    },
  },
  agents: [{
    agentId: 'finops-agent', model: 'mixed', tokensLast24h: null, tokensLast7d: null,
    costLast24hUsd: null, costLast7dUsd: 2.5, budgetMonthlyUsd: null,
    budgetUsedPct: null, burnRate: null, anomalyZ: null,
  }],
  anomalies: [],
}
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
function mount(handler?: (path: string) => Promise<Response> | Response | undefined) {
  vi.stubGlobal('fetch', vi.fn(async (input: string) => handler?.(input) ?? json(
    input.endsWith('/lifecycle') ? lifecycle : input.endsWith('/costs') ? costs : { available: false },
  )))
  return render(<LanguageProvider><FinOpsPage /></LanguageProvider>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('FinOps independent evidence sources', () => {
  it.each(['http', 'json', 'shape', 'network'])('keeps primary evidence after optional %s failure', async failure => {
    mount(path => {
      if (!path.endsWith('/resources')) return
      if (failure === 'network') return Promise.reject(new Error('offline'))
      return failure === 'http' ? json({}, 503) : failure === 'json' ? new Response('{') : json({ available: true, services: [null] })
    })
    expect(await screen.findByText('EKS Support Tier')).toBeInTheDocument()
    expect(await screen.findByText('$4,321')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Retry Resource utilisation' })).toBeEnabled()
  })

  it('renders successful sources while another request is still pending', async () => {
    mount(path => path.endsWith('/resources') ? new Promise<Response>(() => {}) : undefined)
    expect(await screen.findByText('EKS Support Tier')).toBeInTheDocument()
    expect(await screen.findByText('$4,321')).toBeInTheDocument()
    expect(screen.getByText('Loading: Resource utilisation…')).toBeInTheDocument()
  })

  it('keeps costs visible when lifecycle is unavailable and offers an independent retry', async () => {
    let failed = true
    mount(path => path.endsWith('/lifecycle') && failed ? json({}, 503) : undefined)
    expect(await screen.findByText('$4,321')).toBeInTheDocument()
    expect(screen.queryByText('EKS Support Tier')).not.toBeInTheDocument()
    failed = false
    fireEvent.click(await screen.findByRole('button', { name: 'Retry Platform lifecycle' }))
    expect(await screen.findByText('EKS Support Tier')).toBeInTheDocument()
  })

  it('removes stale costs after refresh failure and restores them after a successful retry', async () => {
    let failed = false
    mount(path => path.endsWith('/costs') && failed ? json({}, 503) : undefined)
    expect(await screen.findByText('$4,321')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh FinOps costs' })).toBeEnabled())
    failed = true
    fireEvent.click(screen.getByRole('button', { name: 'Refresh FinOps costs' }))
    await screen.findByRole('button', { name: 'Retry Cloud costs' })
    expect(screen.queryByText('$4,321')).not.toBeInTheDocument()
    expect(screen.getByText('EKS Support Tier')).toBeInTheDocument()
    failed = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry Cloud costs' }))
    expect(await screen.findByText('$4,321')).toBeInTheDocument()
  })

  it('preserves partial metric evidence and its warning instead of claiming complete coverage', async () => {
    mount(path => {
      if (path.endsWith('/resources')) return json({ available: true, degraded: true, error: 'query failed',
        fleetHeapPct: 50, underutilisedCount: 0, serviceCount: 1, collectedAt: '2026-10-19T10:00:00Z',
        services: [{ name: 'example-service', short: 'example', heap: { usedBytes: 100, maxBytes: 200, pct: 50 },
          cpuCoresUsed: null, requestsPerSec: null, efficiency: 'normal' }] })
      if (path.endsWith('/right-sizing')) return json({ available: true, degraded: true, error: 'query failed',
        collectedAt: '2026-10-19T10:00:00Z', fleetCpuEfficiencyPct: 50, fleetMemEfficiencyPct: null,
        highSavingsCount: 0, services: [], vpaHasData: false })
    })
    expect(await screen.findByText(/Resource utilisation: partial evidence/)).toBeInTheDocument()
    expect(await screen.findByText(/Right-sizing: partial evidence/)).toBeInTheDocument()
    expect(await screen.findByText('example')).toBeInTheDocument()
    expect(screen.queryByText('query failed')).not.toBeInTheDocument()
  })

  it('distinguishes unavailable anomaly evidence from a verified empty observation', async () => {
    let available = false
    mount(path => path.endsWith('/anomalies') ? json({ available, anomalies: [] }) : undefined)
    await screen.findByRole('button', { name: 'Retry Cost anomalies' })
    expect(screen.queryByText(/No active cost anomalies/)).not.toBeInTheDocument()
    available = true
    fireEvent.click(screen.getByRole('button', { name: 'Retry Cost anomalies' }))
    expect(await screen.findByText('No active cost anomalies in the latest source response.')).toBeInTheDocument()
  })

  it('keeps incomplete AI costs unknown while showing individually measured values', async () => {
    mount(path => path.endsWith('/ai-costs') ? json(aiCostsWithMissingMeasurements) : undefined)

    const totalLabel = await screen.findByText('Total AI costs — last 7 days')
    expect(totalLabel.parentElement).toHaveTextContent('No data')
    expect(await screen.findByText('$2.50')).toBeInTheDocument()
    const coverageStatus = screen.getAllByRole('status').find(status => /last successful load/i.test(status.textContent ?? ''))
    expect(coverageStatus).toBeDefined()
    expect(coverageStatus).toHaveTextContent('last successful load: none')
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument()
  })
})
