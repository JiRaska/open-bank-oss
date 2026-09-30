// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

function finrepResponse(templateId: string) {
  const cellsByTemplate: Record<string, Array<{ rowRef: string; colRef: string; value: number; currency: string }>> = {
    'F01.01': [{ rowRef: 'r0380', colRef: 'c0010', value: 1250.5, currency: 'CZK' }],
    'F01.02': [{ rowRef: 'r0300', colRef: 'c0010', value: 900, currency: 'CZK' }],
    'F01.03': [{ rowRef: 'r0300', colRef: 'c0010', value: 350.5, currency: 'CZK' }],
    'F02.00': [{ rowRef: 'r0670', colRef: 'c0010', value: 42, currency: 'CZK' }],
  }
  return {
    templateId,
    period: '2026-06-30',
    isBalanced: true,
    cells: cellsByTemplate[templateId],
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('Regulatory report preview', () => {
  it('opens as a labelled modal and restores the trigger after Escape', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('unavailable', { status: 502 })))
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const finrepCard = screen.getByText('CNB — Finanční výkazy (FINREP)').closest('.card')
    const trigger = within(finrepCard as HTMLElement).getByRole('button', { name: 'Preview export' })
    fireEvent.click(trigger)

    const dialog = await screen.findByRole('dialog', { name: /Export preview.*FINREP/i })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'Close export preview' })).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('does not offer a fake export preview for catalogue-only reports', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const paymentsCard = screen.getByText('SDAT — Platební statistika').closest('.card')
    expect(paymentsCard).not.toBeNull()
    expect(within(paymentsCard as HTMLElement).queryByRole('button', { name: 'Preview export' })).not.toBeInTheDocument()
    expect(within(paymentsCard as HTMLElement).getByRole('status')).toHaveTextContent('Preview unavailable')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('loads all four implemented FINREP templates through the authenticated BFF and renders their real cells', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) {
        return new Response(JSON.stringify({ latest: '2026-06-30', periods: ['2026-06-30', '2026-05-31'] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }
      const templateId = ['F01.01', 'F01.02', 'F01.03'].find(id => url.includes(id)) ?? 'F02.00'
      return new Response(JSON.stringify(finrepResponse(templateId)), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    expect(screen.getByText('Connected submission')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit SDAT' })).not.toBeInTheDocument()

    const finrepCard = screen.getByText('CNB — Finanční výkazy (FINREP)').closest('.card')
    expect(finrepCard).not.toBeNull()
    fireEvent.click(within(finrepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5))
    await waitFor(() => {
      expect(screen.queryByText(/FINREP \/ COREP service/)).not.toBeInTheDocument()
      expect(screen.getByText(/Celková aktiva/)).toBeInTheDocument()
      expect(screen.getByText(/Celkové závazky/)).toBeInTheDocument()
      expect(screen.getByText(/Celkový vlastní kapitál/)).toBeInTheDocument()
      expect(screen.getByText(/Zisk \/ ztráta za období/)).toBeInTheDocument()
    })
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/svc/finrep-service/api/v1/finrep/periods')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F01.01?asOf=2026-06-30')
    expect(String(fetchMock.mock.calls[2][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F01.02?asOf=2026-06-30')
    expect(String(fetchMock.mock.calls[3][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F01.03?asOf=2026-06-30')
    expect(String(fetchMock.mock.calls[4][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F02.00?asOf=2026-06-30')
    expect(screen.getByText('finrep-service ← zmrazená ledger předvaha (FROZEN / LINES_V1)')).toBeInTheDocument()
    expect(screen.getByTestId('test-data-watermark')).toHaveTextContent(/TEST DATA/i)
    expect(screen.getByText('TEST_ONLY')).toBeInTheDocument()
    expect(screen.getByText(/NESMÍ BÝT ODESLÁNO REGULÁTOROVI/)).toBeInTheDocument()
    expect(screen.getByTestId('export-readiness')).toHaveTextContent(/Ready for internal export/)
    expect(screen.getByRole('button', { name: 'Export preview as JSON' })).toBeEnabled()
  })

  it('does not present an implemented endpoint as live data when the BFF cannot load it', async () => {
    const fetchMock = vi.fn(async () => new Response('upstream unavailable', { status: 502 }))
    vi.stubGlobal('fetch', fetchMock)

    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    expect(screen.getByText('Implemented preview')).toBeInTheDocument()
    expect(screen.queryByText('Live preview')).not.toBeInTheDocument()
    expect(screen.getByText('Implemented preview coverage')).toBeInTheDocument()

    const finrepCard = screen.getByText('CNB — Finanční výkazy (FINREP)').closest('.card')
    expect(finrepCard).not.toBeNull()
    fireEvent.click(within(finrepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(screen.queryByText('Celková aktiva')).not.toBeInTheDocument()
    expect(screen.queryByText('Živý datový náhled')).not.toBeInTheDocument()
    expect(screen.queryByText('Dostupnost dat')).not.toBeInTheDocument()
  })

  it('shows actual working-preview values but blocks regulatory export when no immutable period exists', async () => {
    const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(
      url.includes('/api/v1/finrep/periods')
        ? { latest: null, periods: [] }
        : finrepResponse(['F01.01', 'F01.02', 'F01.03'].find(id => url.includes(id)) ?? 'F02.00'),
    ), { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const finrepCard = screen.getByText('CNB — Finanční výkazy (FINREP)').closest('.card')
    fireEvent.click(within(finrepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    expect(await screen.findByText(/Working preview of actual values/i)).toBeInTheDocument()
    expect(screen.getByText(/Celková aktiva/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(5)
    expect(String(fetchMock.mock.calls[1][0])).toContain('evidence=LIVE_PREVIEW')
    expect(screen.getByTestId('export-blocked')).toHaveAttribute('data-block-reason', 'provisional_data')
    expect(screen.getByRole('link', { name: /Open regulatory close/i })).toHaveAttribute('href', '/day-end?tab=regulatory')
    expect(screen.getByRole('button', { name: 'Export preview as JSON' })).toBeDisabled()
  })

  it('blocks export when COREP honestly reports a data gap', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
      url.includes('/api/v1/finrep/periods')
        ? { latest: '2026-06-30', periods: ['2026-06-30'] }
        : {
            templateId: 'C_01.00', period: '2026-06-30', hasDataGaps: true,
            cells: [{ rowRef: 'r010', colRef: 'c010', value: 0, currency: 'CZK', isDataGap: true, gapReason: 'capital accounts unavailable' }],
          },
    ), { status: 200, headers: { 'content-type': 'application/json' } })))
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const corepCard = screen.getByText('CNB — Kapitálová přiměřenost (COREP)').closest('.card')
    fireEvent.click(within(corepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))
    await waitFor(() => expect(screen.getByTestId('export-readiness')).toHaveTextContent(/Export blocked.*incomplete data/i))
    expect(screen.getByRole('button', { name: 'Export preview as JSON' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Export preview as CSV' })).toBeDisabled()
  })

  function corepResponse(templateId: string) {
    if (templateId === 'C_02.00') {
      return {
        templateId, period: '2026-06-30', hasDataGaps: true,
        cells: [
          { rowRef: 'r0125', colRef: 'c0010', label: 'Corporates - Other', value: 4200, currency: 'CZK', isDataGap: false },
          {
            rowRef: 'r0010', colRef: 'c0010', label: 'TOTAL RISK EXPOSURE AMOUNT [row code UNVERIFIED]',
            value: 0, currency: 'CZK', isDataGap: true,
            gapReason: 'Article 92(3) TREA includes market, operational and CVA risk, which the risk engine does not compute.',
          },
        ],
      }
    }
    return {
      templateId: 'C_01.00', period: '2026-06-30', hasDataGaps: false,
      cells: [{ rowRef: 'r010', colRef: 'c010', label: 'OWN FUNDS', value: 1000, currency: 'CZK', isDataGap: false }],
    }
  }

  it('renders C 02.00 rows alongside C 01.00, from finrep-service through the same BFF path', async () => {
    const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(
      url.includes('/api/v1/finrep/periods')
        ? { latest: '2026-06-30', periods: ['2026-06-30'] }
        : corepResponse(url.includes('C_02.00') ? 'C_02.00' : 'C_01.00'),
    ), { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const corepCard = screen.getByText('CNB — Kapitálová přiměřenost (COREP)').closest('.card')
    fireEvent.click(within(corepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/v1/corep/templates/C_01.00')
    expect(String(fetchMock.mock.calls[2][0])).toContain('/api/v1/corep/templates/C_02.00')
    expect(await screen.findByText(/OWN FUNDS/)).toBeInTheDocument()
    expect(screen.getByText(/Corporates - Other/)).toBeInTheDocument()
  })

  it('shows a data-gap cell as the gap badge and its reason, never a bare 0', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
      url.includes('/api/v1/finrep/periods')
        ? { latest: '2026-06-30', periods: ['2026-06-30'] }
        : corepResponse(url.includes('C_02.00') ? 'C_02.00' : 'C_01.00'),
    ), { status: 200, headers: { 'content-type': 'application/json' } })))
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const corepCard = screen.getByText('CNB — Kapitálová přiměřenost (COREP)').closest('.card')
    fireEvent.click(within(corepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    const gapCell = await screen.findByText(/DATOVÁ MEZERA/)
    expect(gapCell).toHaveTextContent('DATOVÁ MEZERA — Article 92(3) TREA includes market, operational and CVA risk, which the risk engine does not compute.')
    // The gap row must never render as if r0010's flagged zero were a real, attested balance.
    expect(gapCell.textContent).not.toMatch(/^\s*0[.,]00/)
    expect(screen.queryByText((_, el) => el?.textContent === 'CZK 0.00')).not.toBeInTheDocument()
  })

  it('keeps a "[row code UNVERIFIED]" label marker visible instead of stripping it', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
      url.includes('/api/v1/finrep/periods')
        ? { latest: '2026-06-30', periods: ['2026-06-30'] }
        : corepResponse(url.includes('C_02.00') ? 'C_02.00' : 'C_01.00'),
    ), { status: 200, headers: { 'content-type': 'application/json' } })))
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const corepCard = screen.getByText('CNB — Kapitálová přiměřenost (COREP)').closest('.card')
    fireEvent.click(within(corepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    expect(await screen.findByText(/TOTAL RISK EXPOSURE AMOUNT \[row code UNVERIFIED\]/)).toBeInTheDocument()
  })

  it('gives a clear, risk-engine-specific message when C 02.00 fails to render', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) {
        return new Response(JSON.stringify({ latest: '2026-06-30', periods: ['2026-06-30'] }), {
          status: 200, headers: { 'content-type': 'application/json' },
        })
      }
      if (url.includes('C_02.00')) {
        return new Response(JSON.stringify({
          traceId: 't-1', status: 500, code: 'INTERNAL_ERROR', message: 'risk engine read failed',
        }), { status: 500, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify(corepResponse('C_01.00')), { status: 200, headers: { 'content-type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const corepCard = screen.getByText('CNB — Kapitálová přiměřenost (COREP)').closest('.card')
    fireEvent.click(within(corepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    expect(await screen.findByText(/risk-engine/i)).toBeInTheDocument()
    expect(screen.getByText(/C 02.00/)).toBeInTheDocument()
    expect(screen.queryByText(/OWN FUNDS/)).not.toBeInTheDocument()
  })
})
