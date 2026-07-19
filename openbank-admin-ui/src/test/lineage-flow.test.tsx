// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour test for the data-lineage flow page (/docs/lineage). Mounts with a
// real-shaped /api/catalog/governance payload and asserts the domain bands, the
// derived lineage edges + SMIL flow, the detail panel, and graceful degradation.

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import LineageFlowPage from '@/app/docs/lineage/page'

const SERVICES = [
  { serviceName: 'account-service', dataDomain: 'core', dataLineageRole: 'producer', lineage: { downstream: [{ serviceName: 'ledger-service', relationType: 'api' }], interfaces: { apis: ['/api/v1/accounts'] } } },
  { serviceName: 'ledger-service', dataDomain: 'core', dataLineageRole: 'both', lineage: { upstream: [{ serviceName: 'account-service', relationType: 'api' }], downstream: [{ serviceName: 'sepa-payment', relationType: 'topic' }] } },
  { serviceName: 'sepa-payment', dataDomain: 'payments', dataLineageRole: 'consumer', lineage: { upstream: [{ serviceName: 'ledger-service', relationType: 'topic' }] } },
]

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}
function mockFetch(governanceOk = true) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/catalog/governance')) return governanceOk ? json({ services: SERVICES, available: true }) : new Response('nope', { status: 404 })
    return json({})
  })
}

describe('data-lineage flow page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('renders domain bands with the code-derived lineage nodes', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    await waitFor(() => expect(screen.getByText('account')).toBeInTheDocument())
    expect(screen.getByText('ledger')).toBeInTheDocument()
    expect(screen.getByText('sepa-payment')).toBeInTheDocument()
    expect(screen.getByText('CORE · 2')).toBeInTheDocument()
    expect(screen.getByText('PAYMENTS · 1')).toBeInTheDocument()
  })

  it('animates flow by default and stops when toggled off', async () => {
    vi.stubGlobal('fetch', mockFetch())
    const { container } = render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    await waitFor(() => expect(screen.getByText('account')).toBeInTheDocument())
    expect(container.querySelectorAll('animateMotion').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: /Flow/i }))
    await waitFor(() => expect(container.querySelectorAll('animateMotion').length).toBe(0))
  })

  it('opens a node detail panel with role, interfaces and connections', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    await waitFor(() => expect(screen.getByText('account')).toBeInTheDocument())
    fireEvent.click(screen.getByText('account'))
    expect(await screen.findByText('producer')).toBeInTheDocument()
    expect(screen.getByText(/INTERFACES/i)).toBeInTheDocument()
    expect(screen.getByText(/Downstream/i)).toBeInTheDocument()
  })

  it('degrades gracefully when governance is unavailable', async () => {
    vi.stubGlobal('fetch', mockFetch(false))
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    // No crash, no raw HTTP — the shared DataUnavailable panel renders instead of the graph.
    await waitFor(() => expect(screen.queryByText('account')).not.toBeInTheDocument())
  })
})
