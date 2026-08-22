// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Trace Explorer must render LOADING, EMPTY and FAILED as three distinguishable things
 * (issue #5904).
 *
 * The defect this pins down is the UI form of a control that cannot report its own absence:
 * a panel that looks identical for "the query returned nothing" and "the query never
 * answered" tells the operator the system is healthy when it is blind. It matters here more
 * than usual because the EMPTY state is currently the *honest* one — admin-ui and
 * openbank-app emit no spans at all (#5735; browser instrumentation proposed in #5847) — so
 * "no traces" is the expected steady state, and a failure hidden inside it would never be
 * noticed.
 *
 * Each test asserts BOTH what is shown and what is NOT, because "renders something" is
 * satisfied by all three states at once and would not detect a regression that collapses
 * them back together.
 */

import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TraceExplorerPage from '@/app/observability/traces/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function renderPage() {
  return render(<LanguageProvider><TraceExplorerPage /></LanguageProvider>)
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

describe('Trace Explorer — loading / empty / failed are three distinct states', () => {
  it('LOADING: shows an in-flight indicator, and neither the empty nor the failed panel', async () => {
    // A fetch that never settles holds the page in its first-paint state.
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    renderPage()

    expect(await screen.findByTestId('trace-list-loading')).toBeInTheDocument()
    expect(screen.getByText(/Loading traces from Tempo|Načítám trasy z Tempa/)).toBeInTheDocument()
    // Must NOT claim there is no data, and must NOT claim a failure.
    expect(screen.queryByText(/No data yet|Zatím žádná data/)).not.toBeInTheDocument()
    expect(screen.queryByText(/is not responding|neodpovídá|Failed to load|Načtení selhalo/)).not.toBeInTheDocument()
  })

  it('EMPTY: a successful search returning zero traces says "no data", not a failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ traces: [] })))
    renderPage()

    expect(await screen.findByText(/No data yet|Zatím žádná data/)).toBeInTheDocument()
    // The loading indicator must be gone, and this must not read as an outage.
    expect(screen.queryByTestId('trace-list-loading')).not.toBeInTheDocument()
    expect(screen.queryByText(/is not responding|neodpovídá/)).not.toBeInTheDocument()
  })

  it('FAILED: a 502 from the Tempo BFF says the source did not answer, not "no data"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ status: 'error', error: 'tempo_unreachable' }, 502)))
    renderPage()

    expect(await screen.findByText(/is not responding|neodpovídá/)).toBeInTheDocument()
    // The distinction under test: a failure must never be dressed up as an empty result.
    expect(screen.queryByText(/No data yet|Zatím žádná data/)).not.toBeInTheDocument()
    expect(screen.queryByTestId('trace-list-loading')).not.toBeInTheDocument()
  })

  it('FAILED and EMPTY do not render the same text as each other', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ traces: [] })))
    const emptyView = renderPage()
    await screen.findByText(/No data yet|Zatím žádná data/)
    const emptyText = emptyView.container.textContent ?? ''
    emptyView.unmount()
    vi.unstubAllGlobals()

    vi.stubGlobal('fetch', vi.fn(async () => json({ status: 'error' }, 502)))
    const failedView = renderPage()
    await screen.findByText(/is not responding|neodpovídá/)
    const failedText = failedView.container.textContent ?? ''

    expect(failedText).not.toEqual(emptyText)
  })
})

describe('Trace Explorer — a failed SPAN fetch is not an empty trace', () => {
  const TRACE = { traceID: 'abc123def456', rootServiceName: 'ledger', rootTraceName: 'GET /accounts', durationMs: 42 }

  it('renders the failure panel, not "no data", when the span fetch 502s', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/tempo/api/search') ? json({ traces: [TRACE] }) : json({ error: 'boom' }, 502))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const traceButton = await screen.findByRole('button', { name: /ledger/ })
    traceButton.click()

    await waitFor(() => {
      expect(screen.getByText(/is not responding|neodpovídá/)).toBeInTheDocument()
    })
    // Before the fix this path did `setSpans([])`, so it rendered the no_data panel.
    expect(screen.queryByText(/No data yet.*Trace spans|Zatím žádná data.*Spany/)).not.toBeInTheDocument()
  })

  it('renders "no data" when the trace genuinely carries zero spans', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url.includes('/api/tempo/api/search') ? json({ traces: [TRACE] }) : json({ batches: [] }))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    const traceButton = await screen.findByRole('button', { name: /ledger/ })
    traceButton.click()

    await waitFor(() => {
      expect(screen.getByText(/No data yet|Zatím žádná data/)).toBeInTheDocument()
    })
    expect(screen.queryByText(/is not responding|neodpovídá/)).not.toBeInTheDocument()
  })
})
