// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ReadinessPage from '@/app/system/readiness/page'

const REPORT = {
  schema_version: 1,
  generated_for: '2026-08-31',
  collected_at: '2026-08-31T13:00:00Z',
  source_sha: 'a'.repeat(40),
  scope: { kind: 'service-module-matrix-v1', service_count: 1, money_path_count: 1, roster_sha256: 'b'.repeat(64) },
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

function apiResult(report = REPORT, status: 'ready' | 'stale' | 'image-baked' = 'ready') {
  return { status, source: status === 'image-baked' ? 'image-baked' : 'runtime', report, provenance: null }
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
    expect(screen.queryByText('Services')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry loading the readiness report' })).toBeInTheDocument()
  })

  it('keeps the last verified report when a refresh fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(apiResult()))
      .mockResolvedValueOnce(response({ error: 'unavailable' }, 503))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    await waitFor(() => expect(screen.getByText('ledger')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Refresh production readiness' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('showing the last available report'))
    expect(screen.getByText('ledger')).toBeInTheDocument()
    expect(screen.getAllByText('GO')).toHaveLength(2)
  })

  it('recovers after an explicit retry', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response(apiResult()))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry loading the readiness report' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading the readiness report' }))

    await waitFor(() => expect(screen.getByText('ledger')).toBeInTheDocument())
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('labels the image-baked fallback and shows its collected age and scope', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(apiResult(REPORT, 'image-baked'))))

    await renderPage()

    await waitFor(() => expect(screen.getByText(/image-baked snapshot, not live evidence/)).toBeInTheDocument())
    expect(screen.getByText(/1 service modules, 1 money-path/)).toBeInTheDocument()
    expect(screen.getByText(/source commit aaaaaaaaaaaa/)).toBeInTheDocument()
    expect(screen.getByText('ledger')).toBeInTheDocument()
  })

  it('keeps the report visible while marking an expired runtime snapshot stale', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(apiResult(REPORT, 'stale'))))

    await renderPage()

    await waitFor(() => expect(screen.getByText(/live snapshot is stale/)).toBeInTheDocument())
    expect(screen.getByText('ledger')).toBeInTheDocument()
    expect(screen.getAllByText('GO')).toHaveLength(2)
  })

  it('polls at the bounded interval only while the page is visible', async () => {
    let poll: (() => void) | undefined
    const nativeSetInterval = window.setInterval.bind(window)
    const nativeClearInterval = window.clearInterval.bind(window)
    const intervalSpy = vi.spyOn(window, 'setInterval').mockImplementation((callback, delay) => {
      if (delay === 60_000) {
        poll = callback as () => void
        return 1
      }
      return nativeSetInterval(callback, delay)
    })
    vi.spyOn(window, 'clearInterval').mockImplementation(timer => {
      if (timer !== 1) nativeClearInterval(timer)
    })
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const fetchMock = vi.fn(async () => response(apiResult()))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(intervalSpy).toHaveBeenCalledWith(expect.any(Function), 60_000)
    expect(poll).toBeDefined()

    await act(async () => {
      visibility.mockReturnValue('hidden')
      document.dispatchEvent(new Event('visibilitychange'))
      await Promise.resolve()
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await act(async () => {
      visibility.mockReturnValue('visible')
      document.dispatchEvent(new Event('visibilitychange'))
      await Promise.resolve()
      await Promise.resolve()
    })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

    await act(async () => {
      visibility.mockReturnValue('hidden')
      poll?.()
      await Promise.resolve()
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await act(async () => {
      visibility.mockReturnValue('visible')
      poll?.()
      await Promise.resolve()
      await Promise.resolve()
    })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })
})
