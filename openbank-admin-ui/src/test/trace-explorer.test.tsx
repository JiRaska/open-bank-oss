// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import TraceExplorerPage from '@/app/observability/traces/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

const TRACE = '0123456789abcdef0123456789abcdef'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('Trace Explorer', () => {
  it('searches Tempo and renders the selected OTLP trace as a span waterfall', async () => {
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/auth/session')) return json(null)
      if (url.includes(`/api/tempo/api/traces/${TRACE}`)) return json({ batches: [{
        resource: { attributes: [{ key: 'service.name', value: { stringValue: 'ledger-service' } }] },
        scopeSpans: [{ spans: [{ spanId: 'span-1', name: 'POST /entries', startTimeUnixNano: '1000000', endTimeUnixNano: '2500000' }] }],
      }] })
      if (url.includes('/api/tempo/api/search?')) return json({ traces: [{ traceID: TRACE, rootServiceName: 'ledger-service', rootTraceName: 'POST /entries', durationMs: 1.5 }] })
      return json({})
    })
    vi.stubGlobal('fetch', f)
    render(<SessionProvider><LanguageProvider><TraceExplorerPage /></LanguageProvider></SessionProvider>)

    await waitFor(() => expect(screen.getByText('ledger-service')).toBeInTheDocument())
    expect(f.mock.calls.some(([u]) => String(u).includes('/api/tempo/api/search?limit=20&start='))).toBe(true)
    fireEvent.click(screen.getByText('ledger-service'))
    await waitFor(() => expect(screen.getByText('POST /entries')).toBeInTheDocument())
    expect(f.mock.calls.some(([u]) => String(u) === `/api/tempo/api/traces/${TRACE}`)).toBe(true)
    expect(screen.getByText(/1 spans/)).toBeInTheDocument()
  })

  it('shows an explicit loading state instead of an empty explorer', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
    render(<SessionProvider><LanguageProvider><TraceExplorerPage /></LanguageProvider></SessionProvider>)
    expect(screen.getByRole('status')).toHaveTextContent(/Loading traces from Tempo/i)
  })
})
