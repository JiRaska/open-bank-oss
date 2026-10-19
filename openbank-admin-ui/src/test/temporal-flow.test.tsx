// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour test for the Temporal workflow flow page (/temporal/flow): mounts with
// a live-shaped /api/temporal/status payload and asserts the saga step-chains, the
// aggregate metrics strip, the SMIL flow animation, and graceful degradation when
// Temporal is not deployed.

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import TemporalFlowPage from '@/app/temporal/flow/page'

const DEPLOYED = {
  available: true, temporalDeployed: true,
  metrics: {
    workflows: { scheduled1h: 5, completed1h: 4, failed1h: 1, timedOut1h: 0 },
    latency: { activityScheduleToStartMs: 12, workflowTaskScheduleToStartMs: 8, serverRequestP99Ms: 20 },
    persistence: { requestsPerSec: 3 }, workers: { totalSlotsAvailable: 10, slotsUsed: 2 },
    namespaces: ['openbank'],
  },
}
const NOT_DEPLOYED = { available: true, temporalDeployed: false, metrics: null }

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}
function mockFetch(status: unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/temporal/status')) return json(status)
    return json({})
  })
}

describe('temporal workflow flow page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('renders the saga step-chains and the aggregate metrics strip', async () => {
    vi.stubGlobal('fetch', mockFetch(DEPLOYED))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))
    await waitFor(() => expect(screen.getByText('Domestic payments')).toBeInTheDocument())
    expect(screen.getByText('SEPA payments')).toBeInTheDocument()
    // a documented saga step
    expect(screen.getByText('IBAN + limit validation')).toBeInTheDocument()
    // aggregate live metrics
    expect(screen.getByText('Completed (1h)')).toBeInTheDocument()
    expect(screen.getByText('Failed (1h)')).toBeInTheDocument()
  })

  it('animates flow by default and stops when toggled off', async () => {
    vi.stubGlobal('fetch', mockFetch(DEPLOYED))
    const { container } = render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))
    await waitFor(() => expect(screen.getByText('Domestic payments')).toBeInTheDocument())
    expect(container.querySelectorAll('animateMotion').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: /Flow/i }))
    await waitFor(() => expect(container.querySelectorAll('animateMotion').length).toBe(0))
  })

  it('degrades gracefully when Temporal is not deployed — chains stay, metrics dash out', async () => {
    vi.stubGlobal('fetch', mockFetch(NOT_DEPLOYED))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))
    // the curated sagas still render (they are documented, not live)
    await waitFor(() => expect(screen.getByText('Domestic payments')).toBeInTheDocument())
    expect(screen.getByText(/not detected by prometheus/i)).toBeInTheDocument()
  })

  it('keeps deployment unknown when the API returns null', async () => {
    vi.stubGlobal('fetch', mockFetch({ available: true, temporalDeployed: null, metrics: null }))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))

    await waitFor(() => expect(screen.getByText(/status is unavailable/i)).toBeInTheDocument())
    expect(screen.queryByText(/not detected by prometheus/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/not deployed/i)).not.toBeInTheDocument()
  })

  it('keeps deployment unknown when the status request is non-OK', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null', { status: 200 })
      if (String(input).includes('/api/temporal/status')) return new Response('', { status: 503 })
      return new Response('{}', { status: 200 })
    }))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))

    await waitFor(() => expect(screen.getByText(/status is unavailable/i)).toBeInTheDocument())
    expect(screen.queryByText(/not detected by prometheus/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/not deployed/i)).not.toBeInTheDocument()
  })

  it('keeps deployment unknown when the status request fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/session')) return new Response('null', { status: 200 })
      if (String(input).includes('/api/temporal/status')) throw new TypeError('network unavailable')
      return new Response('{}', { status: 200 })
    }))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))

    await waitFor(() => expect(screen.getByText(/status is unavailable/i)).toBeInTheDocument())
    expect(screen.queryByText(/not detected by prometheus/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/not deployed/i)).not.toBeInTheDocument()
  })

  it('shows partial metrics and unknown workflow types when their query failed', async () => {
    vi.stubGlobal('fetch', mockFetch({
      available: true,
      temporalDeployed: true,
      degraded: true,
      metrics: {
        workflows: { scheduled1h: 5, completed1h: 4, failed1h: null, timedOut1h: 0 },
        latency: { activityScheduleToStartMs: null, workflowTaskScheduleToStartMs: null, serverRequestP99Ms: null },
        namespaces: ['openbank'],
        workflowTypes: null,
      },
    }))
    render(React.createElement(Providers, null, React.createElement(TemporalFlowPage)))

    expect(await screen.findByText(/some metrics are unavailable/i)).toBeInTheDocument()
    expect(screen.getByText(/workflow types could not be verified/i)).toBeInTheDocument()
    expect(screen.queryByText(/no workflow completed in the last hour/i)).not.toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})
