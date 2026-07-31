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

const CAMPAIGN_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'

const LIST = {
  state: 'ok',
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
  sends: [
    { id: 's1', partyId: '05a02ef1-381c-40e7-b73f-d6855eead42e', stepOrder: 1, outcome: 'SENT', occurredAt: '2026-07-31T18:50:09Z' },
    { id: 's2', partyId: '026289e3-0b80-452a-be01-e69034838549', stepOrder: 1, outcome: 'SUPPRESSED_CONSENT', occurredAt: '2026-07-31T18:50:09Z' },
  ],
  sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok' },
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

    await waitFor(() => expect(screen.getByText('smoke-offer')).toBeTruthy())
    expect(screen.getByText('ACTIVE')).toBeTruthy()
    // The checker is shown next to the maker: for an ACTIVE campaign that pair is the
    // audit-relevant fact, and a console that hides it makes four-eyes unverifiable by eye.
    expect(screen.getByText('ops-checker@openbank.local')).toBeTruthy()
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
    await waitFor(() => expect(screen.getByText('SUPPRESSED_CONSENT')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('SENT')).toBeTruthy()
    expect(screen.getByText('TERMINATED_CONSENT_REVOKED')).toBeTruthy()
    // Surfaced as a headline number, not buried in the table.
    expect(screen.getByText('Suppressed sends')).toBeTruthy()
  }, 15000)

  it('a restricted send log is stated, never rendered as "nothing was suppressed"', async () => {
    const restricted = { ...DETAIL, sends: [], sources: { campaign: 'ok', enrolments: 'ok', sends: 'unauthorized' } }
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
})
