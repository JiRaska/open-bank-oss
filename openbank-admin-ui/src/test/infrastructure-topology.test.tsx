// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour test for the infrastructure topology page (/infrastructure/topology).
// render-smoke only covers the all-fetch-fail path; this mounts with real-shaped
// /api/infra/status + /api/infra/lifecycle payloads so the group bands, live status
// overlay, SMIL flow animation, and the node detail panel are actually exercised.

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import InfraTopologyPage from '@/app/infrastructure/topology/page'

const STATUS = {
  argocd: { status: 'UP', latencyMs: 12, checkedAt: '2026-07-18T00:00:00Z' },
  postgres: { status: 'DOWN', latencyMs: null, checkedAt: '2026-07-18T00:00:00Z' },
  prometheus: { status: 'UP', latencyMs: 8, checkedAt: '2026-07-18T00:00:00Z' },
}
const LIFECYCLE = { components: [{ id: 'postgres', urgency: 'patch-available' }] }

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}
function mockFetch() {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/infra/status')) return json(STATUS)
    if (url.includes('/api/infra/lifecycle')) return json(LIFECYCLE)
    return json({})
  })
}

describe('infrastructure topology page', () => {
  beforeEach(() => { vi.stubGlobal('fetch', mockFetch()) })
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('renders the layered bands with real components', async () => {
    render(React.createElement(Providers, null, React.createElement(InfraTopologyPage)))
    await waitFor(() => expect(screen.getByText('ArgoCD')).toBeInTheDocument())
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument()
    expect(screen.getByText('Prometheus')).toBeInTheDocument()
    // Band headers are upper-cased and unique to the SVG bands.
    expect(screen.getByText('AWS SUBSTRATE')).toBeInTheDocument()
    expect(screen.getByText('OBSERVABILITY')).toBeInTheDocument()
  })

  it('animates flow by default and stops when the flow toggle is turned off', async () => {
    const { container } = render(React.createElement(Providers, null, React.createElement(InfraTopologyPage)))
    await waitFor(() => expect(screen.getByText('ArgoCD')).toBeInTheDocument())
    expect(container.querySelectorAll('animateMotion').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: /Data flow/i }))
    await waitFor(() => expect(container.querySelectorAll('animateMotion').length).toBe(0))
  })

  it('opens a node detail panel with live status and connections on click', async () => {
    render(React.createElement(Providers, null, React.createElement(InfraTopologyPage)))
    await waitFor(() => expect(screen.getByText('ArgoCD')).toBeInTheDocument())
    fireEvent.click(screen.getByText('ArgoCD'))
    expect(await screen.findByText(/CONNECTIONS/i)).toBeInTheDocument()
    // ArgoCD deploys Kyverno in the curated backbone → Kyverno appears both as a
    // node pill and as a connection row in the panel (so ≥2 occurrences).
    expect(screen.getAllByText('Kyverno').length).toBeGreaterThanOrEqual(2)
  })

  it('overlays live probe status — a DOWN component shows DOWN in its panel', async () => {
    render(React.createElement(Providers, null, React.createElement(InfraTopologyPage)))
    await waitFor(() => expect(screen.getByText('PostgreSQL')).toBeInTheDocument())
    fireEvent.click(screen.getByText('PostgreSQL'))
    await waitFor(() => expect(screen.getByText('DOWN')).toBeInTheDocument())
  })
})
