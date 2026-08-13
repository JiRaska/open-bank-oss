// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { SessionProvider } from 'next-auth/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import CampaignDetailPage from '@/app/campaigns/[id]/page'
import NewCampaignPage from '@/app/campaigns/new/page'

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const CAMPAIGN_ID = '7b1f1d5e-0d2a-4a6a-8f7e-2c1b9a0d3e4f'

const SEGMENTS = {
  state: 'ok',
  items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'] }],
}

const CADENCES = {
  state: 'ok',
  items: [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }],
}

const TRIGGERS = {
  state: 'ok',
  items: [{ trigger: 'ACCOUNT_OPENED', humanForm: 'when an account is opened' }],
}

function detail(state: string) {
  return {
    campaign: {
      id: CAMPAIGN_ID,
      name: 'spring offer',
      goal: 'promote the savings product',
      segmentRef: { name: 'actives', version: 1 },
      state,
      createdBy: 'marketa',
      approvedBy: null,
      createdAt: '2026-07-31T18:00:00Z',
      steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0 }],
    },
    enrolments: [],
    sends: { items: [], total: 0, page: 0, size: 50 },
    sendSummary: {},
    sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok' },
  }
}

function mockFetch(routes: Record<string, unknown>) {
  return vi.fn(async (url: string) => {
    const match = Object.keys(routes).find(k => String(url).includes(k))
    return { ok: true, status: 200, json: async () => (match ? routes[match] : {}) }
  })
}

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(SessionProvider, null, React.createElement(LanguageProvider, null, children))
}

function renderDetail() {
  return render(
    React.createElement(
      Providers,
      null,
      React.createElement(CampaignDetailPage, { params: Promise.resolve({ id: CAMPAIGN_ID }) }),
    ),
  )
}

describe('campaign studio', () => {
  beforeEach(() => vi.unstubAllGlobals())

  /**
   * ADR-0221 D1 step 2: the audience is a picker over versioned segment artifacts, and ADR-0201 D1
   * forbids typing a definition in. The absence of any way to author a segment here is the design,
   * so it is worth a test — a helpful "add segment" field would be a regression, not a feature.
   */
  it('the audience is chosen from the catalogue and cannot be typed in', async () => {
    vi.stubGlobal('fetch', mockFetch({ '/api/segments': SEGMENTS }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    await waitFor(
      () => expect(document.querySelector('[data-segment]')).toBeTruthy(),
      { timeout: 8000 },
    )
    // Tiles, not a text box: the catalogue is the only source of an audience, so the control offers
    // the choices rather than accepting a name that may not exist (ADR-0201 D1).
    for (const tile of Array.from(document.querySelectorAll('[data-segment]'))) {
      expect(tile.tagName).toBe('BUTTON')
    }
    expect(screen.getByText(/a pull request, not a UI action/)).toBeTruthy()
  }, 15000)

  it('starts a new journey in the app, with a closed deep link, not as an email sequence', async () => {
    vi.stubGlobal('fetch', mockFetch({ '/api/segments': SEGMENTS }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    await waitFor(() => expect(document.querySelector('[data-channel-pick="PUSH"]')).toBeTruthy(), { timeout: 8000 })
    expect(document.querySelector('[data-channel-pick="PUSH"]')?.getAttribute('data-selected')).toBe('true')
    expect(document.querySelector('[data-mobile-destination="0"]')).toBeTruthy()
    expect(screen.getByText(/fixed app deep link/i)).toBeTruthy()
  }, 15000)

  /**
   * ADR-0176 D4 / ADR-0221 D1 step 3: a campaign supplies values, never body text. The fields
   * offered are exactly the template's declared variables — a free-form body field here would be a
   * control the service refuses by construction, which is a worse experience than not offering it.
   */
  it('offers only the declared template variables, never a free-text body', async () => {
    vi.stubGlobal('fetch', mockFetch({ '/api/segments': SEGMENTS }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    // Mobile is the studio's starting surface. E-mail remains available when a campaign genuinely
    // needs its richer template, and that switch—not the initial screen—owns these three fields.
    fireEvent.click(document.querySelector('[data-channel-pick="EMAIL"]')!)
    // The labels are the marketer's words, but the FIELD SET is still exactly the template's
    // declared variables — asserted by id, which carries the variable name the service knows.
    await waitFor(() => expect(document.getElementById('var-0-offerTitle')).toBeTruthy(), {
      timeout: 8000,
    })
    expect(document.getElementById('var-0-offerText')).toBeTruthy()
    expect(document.getElementById('var-0-ctaText')).toBeTruthy()
    expect(screen.getByLabelText('Headline')).toBeTruthy()
    expect(document.querySelector('textarea')).toBeNull()
    // No field beyond the declared three, which is what "never a free-text body" means in practice.
    expect(document.querySelectorAll('[id^="var-0-"]').length).toBe(3)
  }, 15000)

  it('creates a recurring campaign from the served cadence catalogue', async () => {
    let createBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/segments')) return { ok: true, status: 200, json: async () => SEGMENTS }
      if (url.includes('/api/campaigns/cadences')) return { ok: true, status: 200, json: async () => CADENCES }
      if (url.includes('/api/campaigns/triggers')) return { ok: true, status: 200, json: async () => TRIGGERS }
      if (url === '/api/campaigns') {
        createBody = JSON.parse(String(init?.body))
        return { ok: true, status: 200, json: async () => ({ state: 'ok', campaign: { id: CAMPAIGN_ID } }) }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    await waitFor(() => expect(document.querySelector('[data-segment="actives@1"]')).toBeTruthy(), { timeout: 8000 })
    fireEvent.change(document.getElementById('c-name')!, { target: { value: 'Recurring welcome' } })
    fireEvent.change(document.getElementById('c-goal')!, { target: { value: 'Keep new customers engaged' } })
    fireEvent.click(document.querySelector('[data-segment="actives@1"]')!)
    fireEvent.click(document.querySelector('[data-channel-pick="EMAIL"]')!)
    fireEvent.change(document.getElementById('var-0-offerTitle')!, { target: { value: 'Welcome' } })
    fireEvent.change(document.getElementById('var-0-offerText')!, { target: { value: 'Thanks for joining.' } })
    fireEvent.change(document.getElementById('var-0-ctaText')!, { target: { value: 'Explore' } })
    fireEvent.click(document.querySelector('[data-entry-pick="SCHEDULE"]')!)

    await waitFor(() => expect(document.querySelector('[data-cadence]')).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }))

    await waitFor(() => expect(createBody).toMatchObject({ schedule: { cadence: 'DAILY_MORNING' } }))
    expect(createBody).not.toHaveProperty('trigger')
  }, 15000)

  it('authors both content arms and submits a measurable A/B experiment', async () => {
    let createBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/segments')) return { ok: true, status: 200, json: async () => SEGMENTS }
      if (url.includes('/api/campaigns/cadences')) return { ok: true, status: 200, json: async () => CADENCES }
      if (url.includes('/api/campaigns/triggers')) return { ok: true, status: 200, json: async () => TRIGGERS }
      if (url === '/api/campaigns') {
        createBody = JSON.parse(String(init?.body))
        return { ok: true, status: 200, json: async () => ({ state: 'ok', campaign: { id: CAMPAIGN_ID } }) }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    await waitFor(() => expect(document.querySelector('[data-segment="actives@1"]')).toBeTruthy(), { timeout: 8000 })
    fireEvent.change(document.getElementById('c-name')!, { target: { value: 'Two headlines' } })
    fireEvent.change(document.getElementById('c-goal')!, { target: { value: 'Open more accounts' } })
    fireEvent.click(document.querySelector('[data-segment="actives@1"]')!)
    fireEvent.click(document.querySelector('[data-channel-pick="EMAIL"]')!)
    fireEvent.change(document.getElementById('var-0-offerTitle')!, { target: { value: 'A headline' } })
    fireEvent.change(document.getElementById('var-0-offerText')!, { target: { value: 'A copy' } })
    fireEvent.change(document.getElementById('var-0-ctaText')!, { target: { value: 'Open' } })
    fireEvent.click(document.querySelector('[data-conversion-pick="ACCOUNT_OPENED"]')!)
    fireEvent.click(document.getElementById('c-content-experiment')!)

    await waitFor(() => expect(document.getElementById('var-b-0-offerTitle')).toBeTruthy())
    fireEvent.change(document.getElementById('var-b-0-offerTitle')!, { target: { value: 'B headline' } })
    fireEvent.change(document.getElementById('var-b-0-offerText')!, { target: { value: 'B copy' } })
    fireEvent.change(document.getElementById('var-b-0-ctaText')!, { target: { value: 'Try' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }))

    await waitFor(() => expect(createBody).toMatchObject({
      conversionRule: 'ACCOUNT_OPENED',
      steps: [{ variables: { offerTitle: 'A headline' }, variantBVariables: { offerTitle: 'B headline' } }],
    }))
  }, 15000)

  it('a draft can be submitted but not activated from the same screen', async () => {
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: detail('DRAFT') }))
    renderDetail()

    await waitFor(() => expect(screen.getByText('Submit for approval')).toBeTruthy(), { timeout: 8000 })
    // The whole point of four eyes: the author never sees the button that would let them skip it.
    expect(screen.queryByText('Approve and activate')).toBeNull()
  }, 15000)

  it('submits an opted-in consent fallback as part of the step definition', async () => {
    let createBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url) === '/api/campaigns' && init?.method === 'POST') {
        createBody = JSON.parse(String(init.body))
        return { ok: true, status: 201, json: async () => ({ state: 'ok', campaign: { id: CAMPAIGN_ID } }) }
      }
      if (String(url).includes('/api/segments')) return { ok: true, status: 200, json: async () => SEGMENTS }
      if (String(url).includes('/cadences')) return { ok: true, status: 200, json: async () => CADENCES }
      if (String(url).includes('/triggers')) return { ok: true, status: 200, json: async () => TRIGGERS }
      return { ok: true, status: 200, json: async () => ({}) }
    }))
    render(React.createElement(Providers, null, React.createElement(NewCampaignPage)))

    await waitFor(() => expect(document.querySelector('[data-segment="actives@1"]')).toBeTruthy(), { timeout: 8000 })
    fireEvent.change(document.getElementById('c-name')!, { target: { value: 'Fallback offer' } })
    fireEvent.change(document.getElementById('c-goal')!, { target: { value: 'Open more accounts' } })
    fireEvent.click(document.querySelector('[data-segment="actives@1"]')!)
    fireEvent.click(document.querySelector('[data-channel-pick="EMAIL"]')!)
    fireEvent.change(document.getElementById('var-0-offerTitle')!, { target: { value: 'Headline' } })
    fireEvent.change(document.getElementById('var-0-offerText')!, { target: { value: 'Copy' } })
    fireEvent.change(document.getElementById('var-0-ctaText')!, { target: { value: 'Open' } })
    fireEvent.click(document.querySelector('[data-push-fallback="0"] input')!)
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }))

    await waitFor(() => expect(createBody).toMatchObject({ steps: [{ fallbackToPush: true }] }))
  }, 15000)

  /**
   * ADR-0200 D5: maker != checker. The approver is taken from the token, so the creator's own
   * attempt is refused — and a refusal that reads as a generic failure is indistinguishable from an
   * outage, which is how a working control gets reported as a bug.
   */
  it('states who may approve, so the refusal is not read as a malfunction', async () => {
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: detail('PENDING_APPROVAL') }))
    renderDetail()

    await waitFor(() => expect(screen.getByText('Approve and activate')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText(/Someone other than marketa must approve/)).toBeTruthy()
  }, 15000)

  it('only the transitions the current state allows are offered', async () => {
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: detail('ACTIVE') }))
    renderDetail()

    await waitFor(() => expect(screen.getByText('Pause')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('Close')).toBeTruthy()
    // Rendering every button and letting the service reject four of them teaches operators that
    // red messages are normal, which is how a real refusal stops being read.
    expect(screen.queryByText('Submit for approval')).toBeNull()
    expect(screen.queryByText('Resume')).toBeNull()
  }, 15000)

  it('states the configured recurring entry in the campaign detail', async () => {
    const recurring = {
      ...detail('ACTIVE'),
      campaign: {
        ...detail('ACTIVE').campaign,
        schedule: { cadence: 'DAILY_MORNING', endAt: null },
      },
      entryCatalogues: {
        cadences: [{ cadence: 'DAILY_MORNING', humanForm: 'every day at 09:00', zone: 'Europe/Prague' }],
        triggers: [],
      },
    }
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: recurring }))
    renderDetail()

    await waitFor(() => expect(document.querySelector('[data-campaign-entry]')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText(/every day at 09:00 \(Europe\/Prague\)/)).toBeTruthy()
  }, 15000)

  it('states the consent-bound push fallback in campaign detail', async () => {
    const fallbackDetail = {
      ...detail('ACTIVE'),
      campaign: {
        ...detail('ACTIVE').campaign,
        steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, fallbackToPush: true }],
      },
    }
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: fallbackDetail }))
    renderDetail()

    await waitFor(() => expect(document.querySelector('[data-channel-fallback]')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('Fallback channel')).toBeTruthy()
    expect(screen.getByText(/never triggers a second message/i)).toBeTruthy()
  }, 15000)

  it('shows the actual push channel in the send log instead of inferring it from the step', async () => {
    const auditDetail = {
      ...detail('ACTIVE'),
      sends: {
        items: [{
          id: 'send-1', partyId: 'party-1', stepOrder: 1, outcome: 'SENT', channel: 'PUSH',
          deliveryStatus: 'CONFIRMED', occurredAt: '2026-08-12T10:00:00Z',
        }], total: 1, page: 0, size: 50,
      },
    }
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: auditDetail }))
    renderDetail()

    await waitFor(() => expect(screen.getByText('Channel')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('Push')).toBeTruthy()
  }, 15000)

  it('renders a content experiment as A and B measurements, never an automatic winner', async () => {
    const experimentDetail = {
      ...detail('ACTIVE'),
      campaign: {
        ...detail('ACTIVE').campaign,
        conversionRule: 'ACCOUNT_OPENED',
        steps: [{ order: 1, template: 'MARKETING_PRODUCT_OFFER', delaySeconds: 0, variantBVariables: { offerTitle: 'B' } }],
      },
      contentExperiment: {
        a: { assigned: 120, converted: 10, conversionRate: 10 / 120 },
        b: { assigned: 120, converted: 30, conversionRate: 0.25 },
        observedLiftPercentagePoints: 16.666667,
        decision: {
          state: 'B_OUTPERFORMS_A',
          minimumAssignedPerVariant: 100,
          aConfidenceInterval: { lower: 0.04, upper: 0.16 },
          bConfidenceInterval: { lower: 0.18, upper: 0.34 },
        },
      },
      sources: { campaign: 'ok', enrolments: 'ok', sends: 'ok', sendSummary: 'ok', journey: 'ok', contentExperiment: 'ok' },
    }
    vi.stubGlobal('fetch', mockFetch({ [`/api/campaigns/${CAMPAIGN_ID}`]: experimentDetail }))
    renderDetail()

    await waitFor(() => expect(document.querySelector('[data-content-experiment]')).toBeTruthy(), { timeout: 8000 })
    expect(screen.getByText('A/B content comparison')).toBeTruthy()
    expect(screen.getAllByText(/variant B/i)).not.toHaveLength(0)
    expect(screen.getByText(/does not deploy it automatically/i)).toBeTruthy()
  }, 15000)
})
