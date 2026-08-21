// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour tests for the read-only campaign console (#2895). The payloads below are the shapes the
// sandbox actually returned during the #2749 rollout, suppressions included — that is the case the
// screen exists for, so it is the case the tests use.

import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import CampaignsPage from '@/app/campaigns/page'
import CampaignDetailPage from '@/app/campaigns/[id]/page'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

// CampaignDetailPage can now take an operator to the newly created draft. The console tests render
// that client page outside Next's App Router, so provide exactly the router capability it uses.
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const CAMPAIGN_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'

const LIST = {
  state: 'ok',
  summary: [{ campaignId: CAMPAIGN_ID, enrolled: 80, sent: 40, suppressed: 32, failed: 3, outcomes: [{ outcome: 'CONVERTED', count: 5 }] }],
  items: [
    {
      id: CAMPAIGN_ID,
      name: 'smoke-offer',
      goal: 'E2E rollout proof',
      segmentRef: { name: 'actives', version: 1 },
      state: 'ACTIVE',
      createdBy: 'service-account-openbank-services',
      approvedBy: 'ops-checker@openbank.local',
      createdAt: '2026-07-31T18:00:00Z',
    },
  ],
}

const DETAIL = {
  campaign: { ...LIST.items[0], steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0 }] },
  enrolments: [
    { id: 'e1', partyId: '05a02ef1-381c-40e7-b73f-d6855eead42e', state: 'TERMINATED_CONSENT_REVOKED', currentStep: 0 },
    { id: 'e2', partyId: '026289e3-0b80-452a-be01-e69034838549', state: 'ACTIVE', currentStep: 0 },
  ],
  sends: {
    items: [
      { id: 's1', partyId: '05a02ef1-381c-40e7-b73f-d6855eead42e', stepOrder: 1, outcome: 'SENT', occurredAt: '2026-07-31T18:50:09Z' },
      { id: 's2', partyId: '026289e3-0b80-452a-be01-e69034838549', stepOrder: 1, outcome: 'SUPPRESSED_CONSENT', occurredAt: '2026-07-31T18:50:09Z' },
    ],
    total: 2,
    page: 0,
    size: 50,
  },
  // Counts come from the service, not from the page above — a suppressed headline derived from the
  // loaded rows understates every campaign larger than one page.
  sendSummary: { SENT: 1, SUPPRESSED_CONSENT: 1 },
  sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok' },
}

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

function mockFetch(list: unknown, detail: unknown) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (b: unknown) =>
      new Response(JSON.stringify(b), { status: 200, headers: { 'content-type': 'application/json' } })
    if (url.includes('/api/auth/session')) {
      return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    }
    if (/\/api\/campaigns\/[0-9a-f-]{36}/.test(url)) return json(detail)
    if (url.includes('/api/campaigns')) return json(list)
    return json({})
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('campaign console', () => {
  it('lists campaigns with the maker and the checker', async () => {
    vi.stubGlobal('fetch', mockFetch(LIST, DETAIL))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    // The same campaign intentionally appears in the decision desk and in the complete table.
    // A single-element query makes that useful repetition look like a UI regression.
    await waitFor(() => expect(screen.getAllByText('smoke-offer')).toHaveLength(2))
    // The state is now rendered as a human label ("Running"), because a marketer should not have to
    // know what ACTIVE means. Both halves are asserted on purpose: the label is what the reader
    // sees, and the raw enum stays reachable as the title so the screen and the state machine can
    // never end up describing different things. Asserting only the label would let the two drift.
    expect(screen.getAllByText('Running').length).toBeGreaterThan(0)
    expect(screen.getByTitle('ACTIVE')).toBeTruthy()
    // The checker is shown next to the maker: for an ACTIVE campaign that pair is the
    // audit-relevant fact, and a console that hides it makes four-eyes unverifiable by eye.
    expect(screen.getByText('ops-checker@openbank.local')).toBeTruthy()
  })

  it('makes portfolio delivery evidence actionable without calling suppression a failure', async () => {
    vi.stubGlobal('fetch', mockFetch(LIST, DETAIL))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.getByTestId('campaign-delivery-health')).toBeTruthy())
    const panel = screen.getByTestId('campaign-delivery-health')
    expect(panel.textContent).toMatch(/Sent.*40/)
    expect(panel.textContent).toMatch(/Suppressed by policy.*32/)
    expect(panel.textContent).toMatch(/Failed.*3/)
    expect(panel.textContent).toMatch(/Confirmed conversions.*5/)
    expect(panel.textContent).toMatch(/never clicks or an estimate/i)
  })

  it('a refused read does not render as an empty estate', async () => {
    vi.stubGlobal('fetch', mockFetch({ state: 'unauthorized', items: [] }, DETAIL))
    render(React.createElement(Providers, null, React.createElement(CampaignsPage)))

    await waitFor(() => expect(screen.queryByText('No campaigns yet.')).toBeNull())
  })

  it('shows suppressed sends, their reason, and the suppressed count', async () => {
    vi.stubGlobal('fetch', mockFetch(LIST, DETAIL))
    render(
      React.createElement(
        Providers,
        null,
        React.createElement(CampaignDetailPage, { params: Promise.resolve({ id: CAMPAIGN_ID }) }),
      ),
    )

    // The whole point of the screen: SUPPRESSED_CONSENT must be visible as an outcome, because
    // nothing else in the API distinguishes "deliberately skipped" from "never targeted".
    // Human phrasing is what a marketer reads…
    await waitFor(() => expect(screen.getByText('Consent withdrawn')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('Sent')).toBeTruthy()
    // The enrolment table became a counts-by-state summary (PeopleSummary); the label moved
    // with it. Assert the summary's wording, not the old row badge.
    expect(screen.getAllByText(/Withdrew consent/).length).toBeGreaterThan(0)

    // …and the raw enum stays reachable, so the screen and the API never describe different things.
    // Without this, relabelling could quietly drift from the values the service actually emits.
    expect(screen.getByTitle('SUPPRESSED_CONSENT')).toBeTruthy()
    // The enrolment state moved from a `title` on a table row to `data-state` on the summary
    // tile — same rule as the journey canvas: the enum stays queryable, never visible text.
    expect(document.querySelector('[data-state="TERMINATED_CONSENT_REVOKED"]')).toBeTruthy()

    // Surfaced as a headline number, not buried in the table. “Protected” is deliberate:
    // a consent or quiet-hours suppression is a safeguard, not a failed delivery.
    expect(screen.getByText('Protected')).toBeTruthy()
    // And broken down by reason: "2 suppressed" says something is off, "2× quiet hours" says whether to act.
    expect(screen.getByText(/1× Consent withdrawn/)).toBeTruthy()
  }, 15000)

  it('a restricted send log is stated, never rendered as "nothing was suppressed"', async () => {
    const restricted = {
      ...DETAIL,
      sends: { items: [], total: 0, page: 0, size: 50 },
      sendSummary: {},
      sources: { campaign: 'ok', enrolments: 'ok', sends: 'unauthorized', sendSummary: 'unauthorized' },
    }
    vi.stubGlobal('fetch', mockFetch(LIST, restricted))
    render(
      React.createElement(
        Providers,
        null,
        React.createElement(CampaignDetailPage, { params: Promise.resolve({ id: CAMPAIGN_ID }) }),
      ),
    )

    await waitFor(() => expect(screen.getByText('Send log')).toBeTruthy(), { timeout: 8000 })
    // "Nothing sent yet" for a log the caller may not read would be a lie with the same shape as
    // the truth — the exact misreading this screen exists to prevent.
    expect(screen.queryByText('Nothing sent or attempted yet.')).toBeNull()
  }, 15000)

  /**
   * The send log pages, so anything derived from the rows on screen describes the page, not the
   * campaign. This pins the two numbers that would otherwise silently mean "so far on this page":
   * the protected headline and the suppression breakdown, both of which an operator acts on.
   *
   * Falsification: with the previous page-derived implementation the headline reads 1 and the
   * breakdown reads "1× Consent withdrawn" against the same fixture.
   */
  it('headline counts come from the server summary, not from the loaded page', async () => {
    const bigCampaign = {
      ...DETAIL,
      sends: { ...DETAIL.sends, total: 5000 },
      sendSummary: { SENT: 1000, SUPPRESSED_CONSENT: 4000 },
    }
    vi.stubGlobal('fetch', mockFetch(LIST, bigCampaign))
    render(
      React.createElement(
        Providers,
        null,
        React.createElement(CampaignDetailPage, { params: Promise.resolve({ id: CAMPAIGN_ID }) }),
      ),
    )

    await waitFor(() => expect(screen.getByText('Protected')).toBeTruthy(), { timeout: 8000 })
    // Outcome metrics follow the active locale, while the machine-readable reason keeps its raw
    // aggregate below. The English console therefore displays the headline with a thousands mark.
    expect(screen.getByText('4,000')).toBeTruthy()
    expect(screen.getByText(/4000× Consent withdrawn/)).toBeTruthy()

    // And the range states what fraction is on screen: "1–2" alone cannot distinguish the whole
    // log from the first slice of a much larger one.
    expect(screen.getByText(/of\s*5,?000/)).toBeTruthy()
  }, 15000)
})
