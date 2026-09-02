// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Wiring the consoles to the aggregate endpoints (#3294, #3296).
//
// The endpoints are NEWER than the deployed services, so both pages must work two ways, and the
// tests are about the seam between them:
//
//  - with an aggregate, the figures are the whole book and the cap warning must GO (leaving it up
//    is its own lie),
//  - without one, the old derive-from-the-capped-page behaviour must survive intact, warning and
//    all — a silent downgrade to a wrong number is the failure this whole design avoids,
//  - reach columns must be ABSENT rather than zero when campaign reach is unavailable: "0 sent"
//    reads as "we reached nobody", not as "the service cannot say".

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import LendingPage from '@/app/lending/page'
import CampaignsPage from '@/app/campaigns/page'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

const HOUR = 3_600_000

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const APPS = Array.from({ length: 100 }, (_, i) => ({
  id: `a${i}`,
  partyId: `p${i}`,
  status: 'ASSESSMENT',
  createdAt: new Date(Date.now() - HOUR).toISOString(),
  requestedAmount: { amount: 1000, currency: 'CZK' },
}))

const LOANS = [{ id: 'l1', partyId: 'p1', status: 'ACTIVE', principal: { amount: 500, currency: 'CZK' } }]

/** What `/applications/summary` returns: the WHOLE book, far larger than the capped page. */
const APP_SUMMARY = [
  { status: 'ASSESSMENT', count: 640, oldestCreatedAt: new Date(Date.now() - 200 * HOUR).toISOString(), requested: [{ currency: 'CZK', amount: 9_000_000 }] },
  { status: 'DISBURSED', count: 120, oldestCreatedAt: null, requested: [{ currency: 'CZK', amount: 1_000 }] },
]
const LOAN_SUMMARY = [
  { status: 'ACTIVE', count: 300, principal: [{ currency: 'CZK', amount: 7_000_000 }] },
  { status: 'DELINQUENT', count: 9, principal: [{ currency: 'CZK', amount: 100_000 }] },
]

function lendingFetch({ summaries }: { summaries: boolean }) {
  const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/applications/summary')) {
      return summaries ? json(APP_SUMMARY) : new Response('not found', { status: 404 })
    }
    if (url.includes('/loans/summary')) {
      return summaries ? json(LOAN_SUMMARY) : new Response('not found', { status: 404 })
    }
    if (url.includes('/applications/recent')) return json(APPS)
    if (url.includes('/loans/active')) return json(LOANS)
    return json({})
  })
}

describe('lending console + aggregates', () => {
  it('reports the whole book and stops warning about a cap that no longer applies', async () => {
    vi.stubGlobal('fetch', lendingFetch({ summaries: true }))
    render(React.createElement(Providers, null, React.createElement(LendingPage)))

    // Wait on the DATA, not on the node: the board renders all 13 stages from the generated graph
    // before any fetch resolves, so waiting for the node to exist asserts against an empty page.
    await waitFor(() =>
      expect(screen.getByTestId('stage-ASSESSMENT').getAttribute('data-count')).toBe('640'),
    )
    // 300 ACTIVE, not 309: the tile says "active", so the 9 delinquent ones belong to the tile
    // beside it, not to this one. Exposure below is the whole book, delinquent included, because
    // a delinquent loan is still money lent out.
    expect(screen.getByText('Active loans').closest('.stat-card')?.textContent).toMatch(/300/)
    // \s, not a literal space: cs-CZ groups thousands with a NON-BREAKING space (U+00A0), so a
    // plain-space regex fails against text that reads identically on screen.
    expect(screen.getByText('Active loans').closest('.stat-card')?.textContent).toMatch(
      /7(?:[\s,\u00a0])100(?:[\s,\u00a0])000/,
    )
    expect(screen.getByText('Loans in trouble').closest('.stat-card')?.textContent).toMatch(/9/)
    // The cap disclosure must be gone: there is nothing truncated to disclose.
    expect(screen.getByTestId('cap-note').textContent).not.toMatch(/NOT in these numbers/)
  })

  it('falls back to the capped page — and keeps saying so — when the aggregate 404s', async () => {
    vi.stubGlobal('fetch', lendingFetch({ summaries: false }))
    render(React.createElement(Providers, null, React.createElement(LendingPage)))

    // Same reason as above: wait for the count itself.
    await waitFor(() =>
      expect(screen.getByTestId('stage-ASSESSMENT').getAttribute('data-count')).toBe('100'),
    )
    // …and the page says the older ones are missing. A silent downgrade to a capped number that
    // looks like a total is exactly what this design refuses to do.
    expect(screen.getByTestId('cap-note').textContent).toMatch(/NOT in these numbers/)
  })
})

const CAMPAIGNS = [{
  id: 'c1',
  name: 'smoke-offer',
  goal: 'g',
  segmentRef: { name: 'actives', version: 1 },
  state: 'ACTIVE',
  createdBy: 'maker',
  approvedBy: 'checker',
  createdAt: new Date(Date.now() - HOUR).toISOString(),
}]

function campaignFetch(summary: unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/campaigns')) return json({ items: CAMPAIGNS, state: 'ok', summary })
    return json({})
  })
}

describe('campaign console + reach', () => {
  it('shows reach, and flags suppression as the reason a campaign under-delivered', async () => {
    vi.stubGlobal('fetch', campaignFetch([{ campaignId: 'c1', enrolled: 45, sent: 2, suppressed: 43, failed: 0 }]))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByText('Enrolled')).toBeTruthy())
    // Portfolio pulse gives the cross-campaign total while the row keeps the per-campaign
    // number, so both deliberately surface the same single-campaign value.
    expect(screen.getAllByText('45')).toHaveLength(2)
    // 2 sent against 43 suppressed is the whole story — pin the portfolio metric by its own
    // surface rather than counting every identical number across independent UI panels.
    expect(screen.getByTestId('campaign-delivery-health').textContent).toMatch(/Suppressed by policy.*43/)
  })

  it('omits the reach columns entirely when the deployed service cannot answer', async () => {
    vi.stubGlobal('fetch', campaignFetch(null))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    // The decision desk and the table both name the campaign; wait for the complete dashboard.
    await waitFor(() => expect(screen.getAllByText('smoke-offer')).toHaveLength(2))
    // Zeros would read as "we reached nobody" rather than "the service cannot say".
    expect(screen.queryByText('Enrolled')).toBeNull()
    expect(screen.getByTestId('board-footnote').textContent).toMatch(/does not return them yet/)
  })
})
