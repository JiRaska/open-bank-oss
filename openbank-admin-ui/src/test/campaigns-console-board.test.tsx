// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The campaign board tells an operator where work is stuck. The tests are about the ways it could
// tell them something false:
//
//  - tinting a CLOSED campaign by age (finished last month is not a month overdue),
//  - dropping a campaign whose state the UI does not recognise, so it vanishes from the console,
//  - implying the page covers delivery and response, which the list endpoint does not return.

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import { StageBoard, summariseBy, toneFor, wrapLabel } from '@/components/flow/StageBoard'
import CampaignsPage from '@/app/campaigns/page'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

const HOUR = 3_600_000
const NOW = Date.parse('2026-08-02T12:00:00Z')
const at = (h: number) => new Date(NOW - h * HOUR).toISOString()

const campaign = (state: string, hoursAgo: number, i = 0) => ({
  id: `${state}-${i}`,
  name: `Campaign ${state} ${i}`,
  goal: 'goal',
  segmentRef: { name: 'actives', version: 1 },
  state,
  createdBy: 'maker@openbank.local',
  approvedBy: state === 'ACTIVE' ? 'checker@openbank.local' : null,
  createdAt: at(hoursAgo),
})

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('StageBoard primitives', () => {
  it('keeps the OLDEST age per stage, not the newest', () => {
    const s = summariseBy(
      [{ st: 'DRAFT', at: at(2) }, { st: 'DRAFT', at: at(200) }],
      x => x.st, x => x.at, NOW,
    )
    // The queue's health is set by the item that has waited longest; the newest one hides it.
    expect(Math.round(s.get('DRAFT')!.oldestHours!)).toBe(200)
  })

  it('never tones a terminal stage by age', () => {
    expect(toneFor({ count: 3, oldestHours: 900 }, true, 24, 72)).toBe('done')
    expect(toneFor({ count: 3, oldestHours: 900 }, false, 24, 72)).toBe('bad')
  })

  it('wraps a long stage name instead of truncating it', () => {
    expect(wrapLabel('Čeká na schválení')).toEqual(['Čeká na', 'schválení'])
    expect(wrapLabel('Běží')).toEqual(['Běží'])
  })

  it('renders the honest footnote it is given', () => {
    render(React.createElement(Providers, null, React.createElement(StageBoard, {
      stages: [{ key: 'A', label: 'A' }],
      stats: new Map([['A', { count: 1, oldestHours: 1 }]]),
      ariaLabel: 'board',
      footnote: 'delivery is not here',
    })))
    expect(screen.getByTestId('board-footnote').textContent).toBe('delivery is not here')
  })
})

describe('campaigns console', () => {
  const ITEMS = [
    campaign('DRAFT', 3, 1),
    campaign('PENDING_APPROVAL', 100, 2),
    campaign('ACTIVE', 30, 3),
    campaign('CLOSED', 900, 4),
  ]

  function mockFetch(
    items = ITEMS,
    state = 'ok',
    summary?: unknown[],
    engagement?: { state: 'ok' | 'unavailable'; items: unknown[] },
  ) {
    return vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
      if (url.includes('/api/auth/session')) return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
      if (url.includes('/api/campaigns')) {
        return json({ items, state, ...(summary ? { summary } : {}), ...(engagement ? { engagement } : {}) })
      }
      return json({})
    })
  }

  // ONE render, asserted several ways. Four separate renders of this page pushed unrelated files
  // (card-issue-dialog, service-map-topology) past their timeouts under parallel load — the suite's
  // cost is shared, so a heavy test is not only its own problem.
  it('surfaces what waits for an approver, ages it, keeps the enum, and states what is missing', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('stage-PENDING_APPROVAL')).toBeTruthy())

    // 100 hours waiting for an approver is the single most actionable fact on this screen…
    expect(screen.getByTestId('stage-PENDING_APPROVAL').getAttribute('data-tone')).toBe('bad')
    // …and a campaign closed long ago is not a problem, however old it is.
    expect(screen.getByTestId('stage-CLOSED').getAttribute('data-tone')).toBe('done')

    // The list endpoint returns campaign records only. Implying otherwise — or quietly omitting the
    // fact — is what makes a console look like it is reporting performance when it is not.
    expect(screen.getByTestId('board-footnote').textContent).toMatch(/does not return them|nevrací/)

    // Human label for the reader, raw enum still reachable.
    expect(screen.getAllByText('Awaiting approval').length).toBeGreaterThan(0)
    expect(screen.getByTitle('PENDING_APPROVAL')).toBeTruthy()
  })

  it('does not silently swallow a campaign whose state it does not know', async () => {
    vi.stubGlobal('fetch', mockFetch([...ITEMS, campaign('ARCHIVED', 5, 9)]))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('unknown-states')).toBeTruthy())
    expect(screen.getByTestId('unknown-states').textContent).toMatch(/ARCHIVED/)
    // …and it is still in the table, so the row cannot disappear from the console entirely.
    expect(screen.getByText('Campaign ARCHIVED 9')).toBeTruthy()
  })

  it('clicking a stage filters the table to it', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('stage-ACTIVE')).toBeTruthy())
    fireEvent.click(screen.getByTestId('stage-ACTIVE'))

    await waitFor(() => expect(screen.getByTestId('clear-state')).toBeTruthy())
    // The decision desk deliberately keeps the most important next action visible above the
    // filtered inventory. The assertion belongs to the table, which is the surface the stage filter
    // controls; a global text query would now reject the useful priority card as though it were a
    // leaked table row.
    const table = document.querySelector('table')!
    expect(table.textContent).not.toContain('Campaign DRAFT 1')
    expect(table.textContent).toContain('Campaign ACTIVE 3')
  })

  it('puts the next marketing decisions first and names delivery evidence without inventing conversion', async () => {
    vi.stubGlobal('fetch', mockFetch(
      ITEMS,
      'ok',
      [{
        campaignId: 'ACTIVE-3', enrolled: 1240, sent: 1110, suppressed: 96, failed: 34,
        outcomes: [{ outcome: 'CONVERTED', count: 14 }],
      }],
      {
        state: 'ok',
        items: [{
          campaignId: 'ACTIVE-3', impressions: 980, clicks: 176, dismissals: 21,
          firstObservedAt: at(24), lastObservedAt: at(1),
        }],
      },
    ))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('campaign-decision-desk')).toBeTruthy())
    // The control room gives the operator the complete lifecycle before they drill into the
    // decision queue. It names handoff evidence separately from observed app response.
    expect(screen.getByTestId('campaign-control-room').textContent).toMatch(/Brief.*Review.*Live journey.*Evidence/)
    expect(screen.getByTestId('campaign-evidence-strip').textContent).toMatch(/channel handoff only/)
    const decisions = Array.from(document.querySelectorAll('[data-decision-campaign]'))
      .map(node => node.getAttribute('data-decision-campaign'))
    // Approval is a more urgent human decision than an unfinished draft or a live campaign.
    expect(decisions[0]).toBe('PENDING_APPROVAL-2')
    expect(screen.getByTestId('campaign-delivery-pulse').textContent).toMatch(/1,240|1 240/)
    expect(screen.getByTestId('campaign-engagement-pulse').textContent).toMatch(/980.*176.*21.*14/)
    expect(screen.getByTestId('campaign-engagement-ACTIVE-3').textContent).toMatch(/980 impressions.*176 clicks/)
  })

  it('renders not observed and unavailable as states instead of fake zero metrics', async () => {
    vi.stubGlobal('fetch', mockFetch(ITEMS, 'ok', undefined, { state: 'ok', items: [] }))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('campaign-engagement-pulse')).toBeTruthy())
    expect(screen.getByTestId('campaign-engagement-pulse').textContent).toMatch(/Not yet observed/)
    expect(screen.getByTestId('campaign-engagement-ACTIVE-3').textContent).toBe('Not yet observed')
    expect(screen.getByTestId('campaign-engagement-pulse').textContent).not.toMatch(/0 impressions/)
  })

})
