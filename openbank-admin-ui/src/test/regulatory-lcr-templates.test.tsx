// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

// A fresh Response every call — `Response.json()` consumes the body stream once, so a single
// shared instance answers correctly for exactly one fetch and silently breaks (or throws) every
// subsequent one, including across tests in this file.
function periodsOk(): Response {
  return new Response(JSON.stringify({ latest: '2026-06-30', periods: ['2026-06-30'] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

function lcrCell(templateId: string) {
  if (templateId === 'C_76.00') {
    return [
      { rowRef: 'r0010', colRef: 'c0010', label: 'Liquidity buffer [row code UNVERIFIED]', value: 500_000, currency: 'CZK', isDataGap: false },
      {
        rowRef: 'r0030', colRef: 'c0010', label: 'Liquidity coverage ratio (%) [row code UNVERIFIED]',
        value: 123.45, currency: '%', isDataGap: false,
      },
    ]
  }
  return [{ rowRef: 'r0010', colRef: 'c0010', label: `${templateId} row [row code UNVERIFIED]`, value: 1000, currency: 'CZK', isDataGap: false }]
}

function lcrResponse(templateId: string) {
  return new Response(JSON.stringify({
    templateId, period: '2026-06-30', hasDataGaps: false, cells: lcrCell(templateId),
  }), { status: 200, headers: { 'content-type': 'application/json' } })
}

function findLcrCard(): HTMLElement {
  // "CNB — Krytí likvidity (LCR)" also appears in the "Future catalogue deadlines" widget, so
  // locate the report row by its disclosure button rather than by the name text alone.
  const trigger = screen.getByRole('button', { name: /CNB — Krytí likvidity \(LCR\) — (Rozbalit|Sbalit|Expand|Collapse) detail(s)?/ })
  const card = trigger.closest('.card')
  expect(card).not.toBeNull()
  return card as HTMLElement
}

async function openLcrPreview() {
  render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)
  const lcrCard = findLcrCard()
  fireEvent.click(within(lcrCard).getByRole('button', { name: 'Preview export' }))
  return lcrCard
}

afterEach(() => vi.unstubAllGlobals())

describe('Regulatory catalogue — LCR templates', () => {
  it('lists the four COREP LCR templates as an implemented preview under the CNB — Krytí likvidity (LCR) entry', () => {
    vi.stubGlobal('fetch', vi.fn())
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    const lcrCard = findLcrCard()
    // Implemented (has TEMPLATE_PATHS), not "no data source" catalogue-only.
    expect(within(lcrCard).getByRole('button', { name: 'Preview export' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /CNB — Krytí likvidity \(LCR\) — (Rozbalit|Expand) detail(s)?/ }))
    expect(screen.getByText('C 72.00 – Likvidní aktiva')).toBeInTheDocument()
    expect(screen.getByText('C 73.00 – Odtoky')).toBeInTheDocument()
    expect(screen.getByText('C 74.00 – Přítoky')).toBeInTheDocument()
    expect(screen.getByText('C 76.00 – Výpočet LCR')).toBeInTheDocument()
  })

  it('loads all four LCR templates through the authenticated BFF, in catalogue order', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      const templateId = ['C_72.00', 'C_73.00', 'C_74.00', 'C_76.00'].find(id => url.includes(id))
      return lcrResponse(templateId as string)
    })
    vi.stubGlobal('fetch', fetchMock)

    await openLcrPreview()

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5))
    expect(String(fetchMock.mock.calls[1][0])).toContain('/api/v1/corep/templates/C_72.00')
    expect(String(fetchMock.mock.calls[2][0])).toContain('/api/v1/corep/templates/C_73.00')
    expect(String(fetchMock.mock.calls[3][0])).toContain('/api/v1/corep/templates/C_74.00')
    expect(String(fetchMock.mock.calls[4][0])).toContain('/api/v1/corep/templates/C_76.00')
    expect(await screen.findByText(/Liquidity buffer/)).toBeInTheDocument()
  })

  it('renders C 76.00\'s ratio cell (unit "%") as a percentage, never as money', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      const templateId = ['C_72.00', 'C_73.00', 'C_74.00', 'C_76.00'].find(id => url.includes(id))
      return lcrResponse(templateId as string)
    }))

    await openLcrPreview()

    const ratioRow = await screen.findByText(/Liquidity coverage ratio/)
    const row = ratioRow.closest('tr')
    expect(row).not.toBeNull()
    // 123.45 stored as a scaled percentage number renders as "123,45 %" (cs-CZ), never
    // "CZK 123,45" or a bare "123.45" the backend's currency field would otherwise produce.
    expect(row).toHaveTextContent(/123,45\s*%/)
    expect(row?.textContent).not.toMatch(/CZK/)
  })

  it('still shows the gap badge and reason for an LCR data-gap cell, not a bare zero', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      if (url.includes('C_73.00')) {
        return new Response(JSON.stringify({
          templateId: 'C_73.00', period: '2026-06-30', hasDataGaps: true,
          cells: [{
            rowRef: 'r0060', colRef: 'c0060', label: 'Retail deposits subject to higher outflows [row code UNVERIFIED]',
            value: 0, currency: 'CZK', isDataGap: true,
            gapReason: 'The risk engine models no higher-outflow retail deposit category.',
          }],
        }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      const templateId = ['C_72.00', 'C_74.00', 'C_76.00'].find(id => url.includes(id))
      return lcrResponse(templateId as string)
    }))

    await openLcrPreview()

    const gapCell = await screen.findByText(/DATOVÁ MEZERA/)
    expect(gapCell).toHaveTextContent('DATOVÁ MEZERA — The risk engine models no higher-outflow retail deposit category.')
    expect(gapCell.textContent).not.toMatch(/^\s*0[.,]00/)
  })

  it('shows "template not deployed" copy — not a generic error — when the backend answers 400 for an unmerged LCR template', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      if (url.includes('C_72.00')) return lcrResponse('C_72.00')
      // finrep-service on main: `CorepService.getTemplate` throws IllegalArgumentException for
      // an id its `when` does not match, which libs-runtime maps to 400 — this is exactly what
      // C_73.00/C_74.00/C_76.00 answer before their finrep PRs merge.
      return new Response(JSON.stringify({ traceId: 't-1', status: 400, code: 'BAD_REQUEST', message: 'Unknown or unimplemented COREP template: C_73.00' }), {
        status: 400, headers: { 'content-type': 'application/json' },
      })
    }))

    await openLcrPreview()

    expect(await screen.findByText('Šablona zatím není na backendu nasazena')).toBeInTheDocument()
    expect(screen.queryByText(/Failed to load|Načtení selhalo/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Liquidity buffer/)).not.toBeInTheDocument()
  })

  it('shows "template not deployed" copy for a genuine 404 (not the proxy\'s "Unknown service" shape)', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      if (url.includes('C_72.00')) return lcrResponse('C_72.00')
      return new Response(JSON.stringify({ traceId: 't-2', status: 404, code: 'NOT_FOUND', message: 'no such template' }), {
        status: 404, headers: { 'content-type': 'application/json' },
      })
    }))

    await openLcrPreview()

    expect(await screen.findByText('Šablona zatím není na backendu nasazena')).toBeInTheDocument()
  })

  it('does not confuse an unmerged LCR template with the whole finrep-service being undeployed', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      if (url.includes('C_72.00')) return lcrResponse('C_72.00')
      // The proxy's actual "service not deployed" shape (ADR-0051/ADR-0056) — must still read as
      // `not_deployed`, never be swallowed by the new template-not-deployed message.
      return new Response(JSON.stringify({ error: 'Unknown service: finrep-service' }), {
        status: 404, headers: { 'content-type': 'application/json' },
      })
    }))

    await openLcrPreview()

    expect(await screen.findByText(/není v tomto prostředí nasazená/)).toBeInTheDocument()
    expect(screen.queryByText('Šablona zatím není na backendu nasazena')).not.toBeInTheDocument()
  })

  it('labels a template built from a synthetic risk run, with the run id, and stays silent for production', async () => {
    const runId = '0190a4c0-0000-7000-8000-00000000c020'
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/api/v1/finrep/periods')) return periodsOk()
      const templateId = ['C_72.00', 'C_73.00', 'C_74.00', 'C_76.00'].find(id => url.includes(id)) as string
      const body = { templateId, period: '2026-06-30', hasDataGaps: false, cells: lcrCell(templateId) }
      const extra = templateId === 'C_72.00'
        ? { sourceRunId: runId, provenance: 'synthetic' }
        : { sourceRunId: 'prod-run', provenance: 'production' }
      return new Response(JSON.stringify({ ...body, ...extra }), { status: 200, headers: { 'content-type': 'application/json' } })
    }))

    await openLcrPreview()

    const banner = await screen.findByTestId('synthetic-provenance-C_72.00')
    expect(banner).toHaveTextContent(/Syntetická data|Synthetic data/)
    expect(banner).toHaveTextContent(runId)
    expect(screen.queryByTestId('synthetic-provenance-C_73.00')).toBeNull()
    expect(screen.queryByText('prod-run')).toBeNull()
  })
})
