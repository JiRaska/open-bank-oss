// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import SecurityExcellencePage from '@/app/security/excellence/page'
import { classifySecurityKpiFreshness, SECURITY_KPI_MAX_AGE_MS } from '@/lib/security/kpiFreshness'

vi.mock('@/components/auth/AuthGuard', () => ({ AuthGuard: ({ children }: { children: React.ReactNode }) => children }))

const snapshot = (generatedAt: string) => ({
  generatedAt,
  netpol: { available: true, covered: 8, total: 10, coveragePct: 80, gateGreen: true },
  freshness: { available: true, fleetScore: 75, scoredModules: 12, unknownModules: 2 },
  credentials: { available: true, totalSecrets: 12, staticSecrets: 10, withDeadline: 6, overdue: 1, undeclared: 3, overdueFound: true },
  fuzz: { available: true, inScope: 4, tested: 3, coveragePct: 75, totalExercised: 120, excludedCount: 1, runDate: '2026-09-09' },
  threatModels: { available: true, moneyPathTotal: 6, withModel: 5, staleCount: 2, oldestDays: 110 },
  mttr: { available: true, fixedCount: 8, medianFixDays: 2.5, openCount: 1, oldestOpenDays: 4 },
})

function mockSources(kpis: unknown, status?: 'ready' | 'stale') {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/security/kpis') {
      return new Response(JSON.stringify({ available: true, kpis, status }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } })
  }))
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('Security Excellence source freshness', () => {
  it('classifies weekly KPI evidence stale after the eight-day publication window', () => {
    const now = Date.parse('2026-10-19T12:00:00Z')
    expect(classifySecurityKpiFreshness(new Date(now - SECURITY_KPI_MAX_AGE_MS + 1).toISOString(), now)).toBe('fresh')
    expect(classifySecurityKpiFreshness(new Date(now - SECURITY_KPI_MAX_AGE_MS).toISOString(), now)).toBe('stale')
    expect(classifySecurityKpiFreshness(new Date(now + 6 * 60_000).toISOString(), now)).toBe('stale')
  })

  it('shows the snapshot generation time and excludes stale KPI scores after a recent fetch', async () => {
    const generatedAt = new Date(Date.now() - 9 * 24 * 60 * 60 * 1000).toISOString()
    mockSources(snapshot(generatedAt))
    render(<LanguageProvider initialLanguage="en"><SecurityExcellencePage /></LanguageProvider>)

    const freshness = await screen.findByRole('alert')
    expect(freshness).toHaveTextContent('Security KPI snapshot generated:')
    expect(freshness).toHaveTextContent('stale (8-day data-age limit)')
    expect(freshness).toHaveTextContent(new Date(generatedAt).toLocaleString('en-GB'))
    expect(screen.getAllByText('Security KPI snapshot is stale')).toHaveLength(6)
    expect(screen.queryByText(/^Refreshed:/)).not.toBeInTheDocument()
    expect(screen.getByText(/Live-source check completed:/)).toBeInTheDocument()
  })

  it('reports an unavailable snapshot without claiming that a fetch made it fresh', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/security/kpis') {
        return new Response(JSON.stringify({ available: false, reason: 'not_deployed' }), { status: 200 })
      }
      return new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } })
    }))
    render(<LanguageProvider initialLanguage="en"><SecurityExcellencePage /></LanguageProvider>)

    expect(await screen.findByRole('status')).toHaveTextContent('Security KPI snapshot unavailable')
  })

  it('honors the publisher provenance stale state even when generatedAt alone is recent', async () => {
    const generatedAt = new Date().toISOString()
    mockSources(snapshot(generatedAt), 'stale')
    render(<LanguageProvider initialLanguage="en"><SecurityExcellencePage /></LanguageProvider>)

    const freshness = await screen.findByRole('alert')
    expect(freshness).toHaveTextContent('Security KPI snapshot generated:')
    expect(freshness).toHaveTextContent('stale (publisher heartbeat)')
    expect(screen.getAllByText('Security KPI snapshot is stale')).toHaveLength(6)
  })

  it('pauses refresh while hidden, refreshes on return, and removes the visibility listener on unmount', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/security/kpis') {
        return new Response(JSON.stringify({ available: true, kpis: snapshot(new Date().toISOString()) }), { status: 200 })
      }
      return new Response('{}', { status: 503, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<LanguageProvider initialLanguage="en"><SecurityExcellencePage /></LanguageProvider>)
    await screen.findByText(/Live-source check completed:/)
    const initialKpiRequests = fetchMock.mock.calls.filter(([input]) => String(input) === '/api/security/kpis').length

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/security/kpis')).toHaveLength(initialKpiRequests)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/security/kpis')).toHaveLength(initialKpiRequests + 1))

    view.unmount()
    document.dispatchEvent(new Event('visibilitychange'))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/security/kpis')).toHaveLength(initialKpiRequests + 1)
  })
})
