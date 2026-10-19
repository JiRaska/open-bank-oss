// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SystemHealthPage from '@/app/system/health/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { fetchAllServiceConfigSnapshots, fetchAllGovernanceManifests } from '@/lib/api'

vi.mock('@/lib/api', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/api')>(),
  fetchAllServiceConfigSnapshots: vi.fn(),
  fetchAllGovernanceManifests: vi.fn(),
}))
vi.mock('@/components/observability/OperationalEvidence', () => ({ OperationalEvidence: () => null }))
vi.mock('@/components/insights/ContextualInsights', () => ({ ContextualInsights: () => null }))
vi.mock('@/components/governance/CatalogDriftBanner', () => ({ CatalogDriftBanner: () => <div>Catalog comparison</div> }))

const entry = { name: 'ledger-service', port: 8101, status: 'UP', reachable: true, latencyMs: 12 }
const response = (services = [entry]) => new Response(JSON.stringify({ services }), { status: 200 })
const failure = () => new Response('{}', { status: 503 })
const refresh = () => fireEvent.click(screen.getByRole('button', { name: 'Refresh system health' }))
const mount = () => render(<LanguageProvider><SystemHealthPage /></LanguageProvider>)

beforeEach(() => {
  localStorage.setItem('openbank-language', 'en')
  vi.mocked(fetchAllServiceConfigSnapshots).mockResolvedValue([])
  vi.mocked(fetchAllGovernanceManifests).mockResolvedValue({ byService: {}, timestamp: '2026-01-01T00:00:00Z' })
})
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

describe('System Health evidence', () => {
  it('does not manufacture DOWN services or zero counters on collector failure', async () => {
    const fetch = vi.fn().mockResolvedValue(failure())
    vi.stubGlobal('fetch', fetch)
    mount()
    expect(await screen.findByText('Service health could not be verified')).toBeInTheDocument()
    expect(screen.queryByText('DOWN')).not.toBeInTheDocument()
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument()
    expect(screen.queryByText('Catalog comparison')).not.toBeInTheDocument()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('keeps a real all-down response visible as an outage', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([{ ...entry, status: 'DOWN', reachable: false }])))
    mount()
    expect(await screen.findByText('DOWN')).toBeInTheDocument()
    expect(screen.getByText('Unreachable')).toBeInTheDocument()
    expect(screen.queryByText('Service health could not be verified')).not.toBeInTheDocument()
  })

  it('labels the last successful sample as stale and recovers on retry', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response())
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(response([{ ...entry, status: 'DOWN' }])))
    mount()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    const timestamp = screen.getByText(/Last successful check:/).textContent
    refresh()
    expect(await screen.findByText('Showing the last verified health snapshot')).toBeInTheDocument()
    expect(screen.getByText('UP')).toBeInTheDocument()
    expect(screen.getByText(/Last successful check:/).textContent).toBe(timestamp)
    expect(screen.queryByText('Live status · auto-refresh every 15s')).not.toBeInTheDocument()
    refresh()
    expect(await screen.findByText('DOWN')).toBeInTheDocument()
    expect(screen.queryByText('Showing the last verified health snapshot')).not.toBeInTheDocument()
  })

  it('does not let failed optional metadata discard valid health', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()))
    vi.mocked(fetchAllServiceConfigSnapshots).mockRejectedValue(new Error('503'))
    vi.mocked(fetchAllGovernanceManifests).mockRejectedValue(new Error('503'))
    mount()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(await screen.findByText('Some configuration or governance details are unavailable. Service health is checked independently.')).toBeInTheDocument()
  })

  it('updates health while optional metadata is still pending', async () => {
    const fetch = vi.fn().mockResolvedValue(response())
    vi.stubGlobal('fetch', fetch)
    vi.mocked(fetchAllServiceConfigSnapshots).mockReturnValue(new Promise(() => {}))
    mount()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    refresh()
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  })

  it('contains malformed optional metadata without losing the health sample', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()))
    vi.mocked(fetchAllServiceConfigSnapshots).mockResolvedValue(null as never)
    mount()
    expect(await screen.findByText('UP')).toBeInTheDocument()
    expect(await screen.findByText('Some configuration or governance details are unavailable. Service health is checked independently.')).toBeInTheDocument()
  })

  it('shows empty inventory without asserting that the fleet is healthy', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([])))
    mount()
    expect(await screen.findByText('No service health observations')).toBeInTheDocument()
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument()
  })

  it('does not classify UNKNOWN as degraded or DOWN', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([{ ...entry, status: 'UNKNOWN' }])))
    mount()
    expect(await screen.findByText('UNKNOWN')).toBeInTheDocument()
    expect(screen.getByText('Unknown')).toBeInTheDocument()
  })

  it('does not present zero-count summaries before any response', () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    mount()
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument()
    expect(screen.queryByText('Catalog comparison')).not.toBeInTheDocument()
  })
})
