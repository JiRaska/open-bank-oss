// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ReadinessPage from '@/app/system/readiness/page'

const REPORT = {
  generated_for: '2026-08-31',
  dimensions: [{ code: 'C1', name: 'Code' }],
  services: [{
    service: 'ledger',
    money_path: true,
    scores: { C1: 3 },
    evidence: { C1: 'verified' },
    gate: 'GO',
  }],
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

async function renderPage() {
  await act(async () => {
    render(<LanguageProvider><ReadinessPage /></LanguageProvider>)
  })
}

describe('production readiness recovery states', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    cleanup()
  })

  it('labels an initial outage as unavailable instead of blaming an empty collector', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))

    await renderPage()

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('unavailable right now'))
    expect(screen.queryByText(/No data — run prod-readiness-collector/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry loading the readiness report' })).toBeInTheDocument()
  })

  it('keeps the last verified report when a refresh fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(REPORT))
      .mockResolvedValueOnce(response({ error: 'unavailable' }, 503))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    await waitFor(() => expect(screen.getByText('ledger')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Refresh production readiness' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('showing the last available report'))
    expect(screen.getByText('ledger')).toBeInTheDocument()
    expect(screen.getAllByText('GO')).toHaveLength(2)
  })

  it('does not present an old baked scorecard as live readiness', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(REPORT)))
    await renderPage()
    expect(await screen.findByText(/This report is over two days old/)).toBeInTheDocument()
    expect(screen.getByText(/not live service health/)).toBeInTheDocument()
  })

  it('labels a report with no valid evidence date as unverified', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...REPORT, generated_for: '' })))
    await renderPage()

    expect(await screen.findByText(/Evidence date is unknown/)).toBeInTheDocument()
    expect(screen.queryByText(/Use Service Health for current pod status/)).not.toBeInTheDocument()
  })

  it('recovers after an explicit retry', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response(REPORT))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry loading the readiness report' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading the readiness report' }))

    await waitFor(() => expect(screen.getByText('ledger')).toBeInTheDocument())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
