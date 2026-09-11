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

  it('shows a loading state before the first evidence response', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)))
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    expect(screen.getByTestId('lineage-loading')).toHaveTextContent(/Loading verified data lineage/i)
    expect(screen.queryByText('CORE · 0')).not.toBeInTheDocument()
  })

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

  it('distinguishes a missing bundled snapshot from verified no data', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null')
      return new Response(JSON.stringify({ available: false, services: [] }), { headers: { 'content-type': 'application/json' } })
    }))
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    expect(await screen.findByText(/not deployed|není nasazena/i)).toBeInTheDocument()
    expect(screen.queryByText(/No data yet|Zatím žádná data/i)).not.toBeInTheDocument()
  })

  it('rejects malformed 200 evidence instead of rendering an empty graph', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null')
      return new Response(JSON.stringify({ available: true, services: 'invalid' }), { headers: { 'content-type': 'application/json' } })
    }))
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    expect(await screen.findByText(/Failed to load|Načtení selhalo/i)).toBeInTheDocument()
    expect(screen.queryByText(/No data yet|Zatím žádná data/i)).not.toBeInTheDocument()
  })

  it('retains the last verified map when refresh evidence is malformed', async () => {
    let governanceReads = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null')
      governanceReads += 1
      const body = governanceReads === 1
        ? { services: SERVICES, available: true }
        : { available: true, services: 'invalid' }
      return new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    expect(await screen.findByText('account')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Refresh|Obnovit/ }))
    expect(await screen.findByText(/last verified map|poslední ověřená mapa/i)).toBeInTheDocument()
    expect(screen.getByText('account')).toBeInTheDocument()
  })

  it('purges the verified map when a refresh loses authorization', async () => {
    let governanceReads = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null')
      governanceReads += 1
      return governanceReads === 1
        ? new Response(JSON.stringify({ services: SERVICES, available: true }), { headers: { 'content-type': 'application/json' } })
        : new Response('unauthorized', { status: 401 })
    }))
    render(React.createElement(Providers, null, React.createElement(LineageFlowPage)))
    expect(await screen.findByText('account')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Refresh|Obnovit/ }))
    expect(await screen.findByText(/Session expired|Vypršela relace/i)).toBeInTheDocument()
    expect(screen.queryByText('account')).not.toBeInTheDocument()
  })
})
