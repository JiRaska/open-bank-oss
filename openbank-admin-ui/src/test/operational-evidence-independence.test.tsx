// SPDX-License-Identifier: Apache-2.0

import { cleanup, render, screen, within } from '@testing-library/react'
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
        available: true, configured: 1, monitored: 0, objectives: [],
      }))
      if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
      if (url.includes('/tempo/api/search')) return Promise.resolve(response(503, { error: 'unavailable' }))
      throw new Error(`unexpected fetch: ${url}`)
    }))

    render(<LanguageProvider initialLanguage="en"><OperationalEvidence /></LanguageProvider>)

    expect(await screen.findByText('3 OK')).toBeVisible()
    expect(await screen.findByText('Tempo evidence is unavailable; request duration cannot be verified.')).toBeVisible()
    expect(screen.getByText('SLOs are configured but do not have enough traffic samples yet.')).toBeVisible()
  })
})

function renderPyrraBudgets(configured: number, budgets: (number | null)[], language: 'en' | 'cs' = 'en', names?: string[]) {
  const summary = {
    available: true, configured, monitored: configured,
    objectives: budgets.map((budgetRemaining, index) => ({ name: names?.[index] ?? `journey-${index}`, budgetRemaining, availability: 1 })),
  }
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/pyrra/summary')) {
      const result = response(200, summary)
      // Exercise the boundary's finite-number check even for malformed parsed payloads.
      vi.spyOn(result, 'json').mockResolvedValue(summary)
      return Promise.resolve(result)
    }
    if (url.includes('/temporal/status')) return Promise.resolve(response(200, TEMPORAL))
    if (url.includes('/tempo/api/search')) return Promise.resolve(response(200, TEMPO))
    throw new Error(`unexpected fetch: ${url}`)
  }))
  render(<LanguageProvider initialLanguage={language}><OperationalEvidence /></LanguageProvider>)
  return within(screen.getByText('Pyrra').closest('article')!)
}

describe('Pyrra remaining budget coverage', () => {
  it('marks complete healthy coverage and labels the minimum as remaining error budget', async () => {
    const card = renderPyrraBudgets(2, [1, 0.85])
    expect(await card.findByText('85%')).toBeVisible()
    expect(card.getByText('Healthy')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 2/2 selected customer journeys')).toBeVisible()
    expect(card.getByText('Lowest remaining error budget: journey-1.')).toBeVisible()
  })

  it('keeps partial positive coverage on Watch even when every journey has request metrics', async () => {
    const card = renderPyrraBudgets(6, [1, null, Number.NaN, Number.POSITIVE_INFINITY])
    expect(await card.findByText('100%')).toBeVisible()
    expect(card.getByText('Watch')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 1/6 selected customer journeys')).toBeVisible()
    expect(card.queryByText('Healthy')).not.toBeInTheDocument()
  })

  it('keeps absent and non-finite budgets neutral without fabricating a percentage', async () => {
    const card = renderPyrraBudgets(6, [null, Number.NaN, Number.NEGATIVE_INFINITY])
    expect(await card.findByText('SLOs are configured but do not have enough traffic samples yet.')).toBeVisible()
    expect(card.getByText('No signal')).toBeVisible()
    expect(card.getByText('—')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 0/6 selected customer journeys')).toBeVisible()
    expect(card.queryByText(/NaN|Infinity|%/)).not.toBeInTheDocument()
  })

  it.each([-0.05, -0.0001])('prioritizes negative budget %s over partial coverage even when its percentage rounds to zero', async budget => {
    const card = renderPyrraBudgets(6, [budget, null])
    expect(await card.findByText(`${Math.round(budget * 100)}%`)).toBeVisible()
    expect(card.getByText('Action')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 1/6 selected customer journeys')).toBeVisible()
    expect(card.queryByText('Watch')).not.toBeInTheDocument()
  })

  it('does not mistake duplicate objective observations for complete journey coverage', async () => {
    const card = renderPyrraBudgets(2, [1, 1], 'en', ['same-journey', 'same-journey'])
    expect(await card.findByText('100%')).toBeVisible()
    expect(card.getByText('Watch')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 1/2 selected customer journeys')).toBeVisible()
    expect(card.queryByText('Healthy')).not.toBeInTheDocument()
  })

  it('keeps duplicate negative observations actionable without inflating coverage', async () => {
    const card = renderPyrraBudgets(2, [1, -0.1], 'en', ['same-journey', 'same-journey'])
    expect(await card.findByText('-10%')).toBeVisible()
    expect(card.getByText('Action')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 1/2 selected customer journeys')).toBeVisible()
  })

  it('does not claim healthy coverage when an extra finite observation has no name', async () => {
    const card = renderPyrraBudgets(2, [1, 1, 1], 'en', ['journey-a', 'journey-b', '   '])
    expect(await card.findByText('100%')).toBeVisible()
    expect(card.getByText('Watch')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 2/2 selected customer journeys')).toBeVisible()
    expect(card.queryByText('Healthy')).not.toBeInTheDocument()
  })

  it('keeps an unnamed negative observation actionable beside complete named coverage', async () => {
    const card = renderPyrraBudgets(2, [1, 1, -0.1], 'en', ['journey-a', 'journey-b', ''])
    expect(await card.findByText('-10%')).toBeVisible()
    expect(card.getByText('Action')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 2/2 selected customer journeys')).toBeVisible()
    expect(card.queryByText('Healthy')).not.toBeInTheDocument()
  })

  it('counts a finite zero budget as observed and exhausted, not absent', async () => {
    const card = renderPyrraBudgets(1, [0])
    expect(await card.findByText('0%')).toBeVisible()
    expect(card.getByText('Watch')).toBeVisible()
    expect(card.getByText('30-day SLO · budgets for 1/1 selected customer journeys')).toBeVisible()
    expect(card.queryByText('No signal')).not.toBeInTheDocument()
  })

  it('states the same scoped coverage and remaining-budget meaning in Czech', async () => {
    const card = renderPyrraBudgets(6, [1, null], 'cs')
    expect(await card.findByText('100%')).toBeVisible()
    expect(card.getByText('Pozor')).toBeVisible()
    expect(card.getByText('30denní SLO · rozpočty pro 1/6 vybraných zákaznických cest')).toBeVisible()
    expect(card.getByText('Nejnižší zbývající error budget: journey-0.')).toBeVisible()
  })
})
