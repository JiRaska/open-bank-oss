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
  it('loads the two implemented FINREP templates through the authenticated BFF and renders their real cells', async () => {
    const fetchMock = vi.fn(async (url: string) => {
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

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await waitFor(() => {
      expect(screen.queryByText(/FINREP \/ COREP service/)).not.toBeInTheDocument()
      expect(screen.getByText(/Celková aktiva/)).toBeInTheDocument()
      expect(screen.getByText(/Čistý zisk \/ ztráta/)).toBeInTheDocument()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F01.01?asOf=')
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/svc/finrep-service/api/v1/finrep/templates/F02.00?asOf=')
    expect(screen.getByText('finrep-service ← ledger trial balance (ne ClickHouse)')).toBeInTheDocument()
  })
})
