// SPDX-License-Identifier: Apache-2.0

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OperationalEvidence } from '@/components/observability/OperationalEvidence'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

const TEMPORAL = {
  available: true,
  temporalDeployed: true,
  metrics: { workflows: { scheduled1h: 4, completed1h: 3, failed1h: 0, timedOut1h: 0 } },
}
const TEMPO = { traces: [{ traceID: '0123456789abcdef', durationMs: 1500, rootTraceName: 'payment' }] }

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('operational evidence independence', () => {
  it('shows Temporal and Tempo while Pyrra is still pending', async () => {
    const pyrra = new Promise<Response>(() => {})
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/pyrra/summary')) return pyrra
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(200, TEMPO))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('3 OK')).toBeVisible()
    expect(await screen.findByText('1.50 s')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Refresh operational evidence' })).toHaveAttribute('aria-busy', 'true')
  })

  it('preserves evidence from healthy sources when Pyrra returns an error', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/pyrra/summary')) return Promise.resolve(response(503, { error: 'unavailable' }))
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(200, TEMPO))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('3 OK')).toBeVisible()
    expect(await screen.findByText('1.50 s')).toBeVisible()
    expect(await screen.findByText('No signal')).toBeVisible()
    expect(screen.getByText('Pyrra evidence is unavailable; the remaining budget cannot be verified.')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Refresh operational evidence' })).toHaveAttribute('aria-busy', 'false')
  })

  it('distinguishes unavailable Tempo from an available source with no traces', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/pyrra/summary')) return Promise.resolve(response(200, {
        available: true, configured: 28, monitored: 0, objectives: [],
      }))
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(503, { error: 'unavailable' }))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('3 OK')).toBeVisible()
    expect(await screen.findByText('Tempo evidence is unavailable; request duration cannot be verified.')).toBeVisible()
    expect(screen.getByText('0/28')).toBeVisible()
    expect(screen.getByText('Incomplete')).toBeVisible()
    expect(screen.getByText('SLOs are configured but do not have enough traffic samples yet. Coverage: 0/28.')).toBeVisible()
  })

  it('marks one healthy observed SLO out of 28 as incomplete rather than healthy', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/pyrra/summary')) return Promise.resolve(response(200, {
        available: true, configured: 28, monitored: 1,
        objectives: [{ name: 'openbank-transaction-availability', budgetRemaining: 0.8, availability: 0.9998 }],
      }))
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(200, TEMPO))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('1/28')).toBeVisible()
    expect(screen.getByText('Incomplete')).toBeVisible()
    expect(screen.getByText(/Coverage: 1\/28\. Lowest observed error budget: 80%/)).toBeVisible()
    expect(screen.getByText('Incomplete').closest('article')).not.toHaveTextContent('Healthy')
  })

  it('keeps the observed bad budget visible when coverage is incomplete', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/pyrra/summary')) return Promise.resolve(response(200, {
        available: true, configured: 28, monitored: 1,
        objectives: [{ name: 'openbank-transaction-availability', budgetRemaining: -0.1, availability: 0.9989 }],
      }))
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(200, TEMPO))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('-10%')).toBeVisible()
    expect(screen.getByText('Action')).toBeVisible()
    expect(screen.getByText(/Coverage: 1\/28\. Lowest observed error budget: -10%/)).toBeVisible()
  })
})
