// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour test for the animated data-flow tiers on /docs/service-map.
// render-smoke only covers the empty/no-data path (fetch fails); this mounts the
// page with a code-derived-shaped graph so the infra + external tier rendering,
// the SMIL flow animation, and the tier detail panel are actually exercised —
// the parts render-smoke can't reach. The fixture mirrors the shape emitted by
// scripts/generate-service-graph.mjs (nodes/edges + infra/external tiers), and
// is hermetic so it runs in CI without the gitignored build artifact.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import ServiceMapPage from '@/app/docs/service-map/page'

// Minimal graph in the /api/catalog/graph shape. Sources are modules that ARE on
// the service map (account, notification, agent) so their tier edges anchor and
// their tier nodes render. APNs is wired-but-disabled (enabled:false).
const GRAPH = {
  schema: 'openbank.service-graph/v1',
  nodes: [
    { name: 'openbank-account-service', short: 'account-service', dependsOn: 1, dependedOnBy: 0 },
    { name: 'openbank-ledger-service', short: 'ledger-service', dependsOn: 0, dependedOnBy: 1 },
    { name: 'openbank-notification-service', short: 'notification-service', dependsOn: 0, dependedOnBy: 0 },
    { name: 'openbank-agent-service', short: 'agent-service', dependsOn: 0, dependedOnBy: 0 },
  ],
  edges: [{ from: 'openbank-account-service', to: 'openbank-ledger-service', via: 'ledger-service', type: 'rest' }],
  danglingTopics: [],
  infraNodes: [
    { id: 'infra:postgres', kind: 'infra', tech: 'postgres', label: 'PostgreSQL' },
    { id: 'infra:kafka', kind: 'infra', tech: 'kafka', label: 'Apache Kafka' },
  ],
  externalNodes: [
    { id: 'ext:apple-apns', kind: 'external', vendor: 'Apple', label: 'APNs (push)' },
    { id: 'ext:llm-gateway', kind: 'external', vendor: 'LLM providers', label: 'LLM gateway' },
  ],
  infraEdges: [
    { from: 'openbank-account-service', to: 'infra:postgres', type: 'db' },
    { from: 'openbank-account-service', to: 'infra:kafka', type: 'broker' },
  ],
  externalEdges: [
    { from: 'openbank-notification-service', to: 'ext:apple-apns', type: 'push', enabled: false },
    { from: 'openbank-agent-service', to: 'ext:llm-gateway', type: 'llm', enabled: true },
  ],
  available: true,
}

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

// Answer the page's three mount fetches; everything else is a benign empty payload
// so unrelated widgets (drift banner) degrade quietly.
function mockFetch() {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/catalog/graph')) return json(GRAPH)
    if (url.includes('/api/services/health')) return json({ services: [] })
    if (url.includes('/api/services/governance')) return json({ byService: {} })
    return json({})
  })
}

describe('service-map data-flow tiers', () => {
  beforeEach(() => { vi.stubGlobal('fetch', mockFetch()) })
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('renders the infra + external bands with their code-derived nodes', async () => {
    render(React.createElement(Providers, null, React.createElement(ServiceMapPage)))
    await waitFor(() => expect(screen.getByText('PostgreSQL')).toBeInTheDocument())
    expect(screen.getByText('Apache Kafka')).toBeInTheDocument()
    // Band headers are rendered upper-cased and are unique to the SVG bands.
    expect(screen.getByText('INFRASTRUCTURE')).toBeInTheDocument()
    expect(screen.getByText('EXTERNAL / 3RD PARTIES')).toBeInTheDocument()
    // External 3rd parties whose source service is on the map.
    expect(screen.getByText('LLM gateway')).toBeInTheDocument()
    expect(screen.getByText('Apple APNs')).toBeInTheDocument()
  })

  it('animates flow by default and stops when the flow toggle is turned off', async () => {
    const { container } = render(React.createElement(Providers, null, React.createElement(ServiceMapPage)))
    await waitFor(() => expect(screen.getByText('PostgreSQL')).toBeInTheDocument())
    // Default (no reduce-motion in jsdom) → particles are mounted (service edge at least).
    expect(container.querySelectorAll('animateMotion').length).toBeGreaterThan(0)
    // Toggling "Data flow" off removes every particle (static diagram).
    fireEvent.click(screen.getByRole('button', { name: /Data flow/i }))
    await waitFor(() => expect(container.querySelectorAll('animateMotion').length).toBe(0))
  })

  it('opens a tier detail panel listing the connected services on click', async () => {
    render(React.createElement(Providers, null, React.createElement(ServiceMapPage)))
    await waitFor(() => expect(screen.getByText('Apache Kafka')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Apache Kafka'))
    expect(await screen.findByText(/CONNECTED SERVICES/i)).toBeInTheDocument()
    // account-service produces to Kafka in the fixture → shown as a consumer of the broker.
    expect(screen.getByText('Account Service')).toBeInTheDocument()
  })

  it('draws the wired-but-disabled external integration without a flow particle', async () => {
    const { container } = render(React.createElement(Providers, null, React.createElement(ServiceMapPage)))
    await waitFor(() => expect(screen.getByText('Apple APNs')).toBeInTheDocument())
    // The disabled APNs edge (notification→apns, enabled:false) must never animate,
    // even with flow on and its 3rd-parties band shown by default.
    const apnsParticles = [...container.querySelectorAll('animateMotion')]
      .filter(m => (m.querySelector('mpath')?.getAttribute('href') ?? '').includes('apple-apns'))
    expect(apnsParticles.length).toBe(0)
  })
})
