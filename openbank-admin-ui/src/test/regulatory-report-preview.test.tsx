// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

function finrepResponse(templateId: string) {
  if (templateId === 'F01.01') {
    return {
      templateId,
      period: '2026-06-30',
      isBalanced: true,
      cells: [{ rowRef: 'r010', colRef: 'c010', value: 1250.5, currency: 'CZK' }],
    }
  }
  return {
    templateId,
    period: '2026-06-30',
    isBalanced: true,
    cells: [{ rowRef: 'r450', colRef: 'c010', value: 42, currency: 'CZK' }],
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('Regulatory report preview', () => {
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

  it('loads the two implemented FINREP templates through the authenticated BFF and renders their real cells', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) {
        return new Response(JSON.stringify({ latest: '2026-06-30', periods: ['2026-06-30', '2026-05-31'] }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }
      const templateId = url.includes('F01.01') ? 'F01.01' : 'F02.00'
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

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    await waitFor(() => {
      expect(screen.queryByText(/FINREP \/ COREP service/)).not.toBeInTheDocument()
      expect(screen.getByText(/Celková aktiva/)).toBeInTheDocument()
      expect(screen.getByText(/Čistý zisk \/ ztráta/)).toBeInTheDocument()
    })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/svc/finrep-service/api/v1/finrep/periods')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F01.01?asOf=2026-06-30')
    expect(String(fetchMock.mock.calls[2][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F02.00?asOf=2026-06-30')
    expect(screen.getByText('finrep-service ← zmrazená ledger předvaha (FROZEN / LINES_V1)')).toBeInTheDocument()
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
        : finrepResponse(url.includes('F01.01') ? 'F01.01' : 'F02.00'),
    ), { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const finrepCard = screen.getByText('CNB — Finanční výkazy (FINREP)').closest('.card')
    fireEvent.click(within(finrepCard as HTMLElement).getByRole('button', { name: 'Preview export' }))

    expect(await screen.findByText(/Working preview of actual values/i)).toBeInTheDocument()
    expect(screen.getByText(/Celková aktiva/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(String(fetchMock.mock.calls[1][0])).toContain('evidence=LIVE_PREVIEW')
    expect(screen.getByTestId('export-blocked')).toHaveAttribute('data-block-reason', 'provisional_data')
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
})
