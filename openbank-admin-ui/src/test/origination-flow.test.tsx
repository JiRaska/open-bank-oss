// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The origination flow exists to make a diagram that CANNOT quietly disagree with the state
// machine. So the tests here are mostly about that disagreement, not about pixels:
//
//  - every state the generator found must have a human label (adding a state in Kotlin goes red
//    here instead of rendering a raw enum name at a business reader),
//  - the derived happy path must cover every non-terminal state (a state added off the first-edge
//    chain goes red instead of silently vanishing from the diagram),
//  - a restricted evidence read must never render as "no history".

import { describe, it, expect, afterEach, vi } from 'vitest'
import React, { Suspense } from 'react'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from '@/components/auth/SessionProvider'
import {
  ORIGINATION_GRAPH,
  STATE_LABELS,
  happyPath,
  exitStates,
  OriginationFlow,
} from '@/components/lending/OriginationFlow'
import ApplicationFlowPage from '@/app/lending/applications/[id]/page'

const APP_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'

const APPLICATION = {
  id: APP_ID,
  partyId: '05a02ef1-381c-40e7-b73f-d6855eead42e',
  status: 'FOUR_EYES',
  requestedAmount: { amount: 250000, currency: 'CZK' },
  jurisdiction: 'CZ',
  productType: 'CONSUMER_CREDIT',
  packVersion: 1,
}

const ev = (from: string, to: string, actor: string, kind: string, at: string) => ({
  eventId: `${to}-id`,
  eventType: 'credit.application.transition',
  occurredAt: at,
  payload: JSON.stringify({
    fromState: from, toState: to, actorId: actor, actorKind: kind,
    reason: 'operator advance', occurredAt: at,
  }),
})

const EVIDENCE = {
  applicationId: APP_ID,
  eventCount: 3,
  events: [
    ev('DRAFT', 'SUBMITTED', 'app@customer', 'HUMAN', '2026-08-01T09:00:00Z'),
    ev('SUBMITTED', 'KYC_PENDING', 'kyc-bot', 'SYSTEM', '2026-08-01T09:05:00Z'),
    ev('DECISION_PENDING', 'FOUR_EYES', 'risk@openbank.local', 'HUMAN', '2026-08-01T11:00:00Z'),
  ],
}

/** `use(params)` suspends, so the page needs a boundary the way the App Router gives it one. */
function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(
    SessionProvider, null,
    React.createElement(
      LanguageProvider, null,
      React.createElement(Suspense, { fallback: React.createElement('div', null, 'loading') }, children),
    ),
  )
}

function mockFetch(app: { status: number; body: unknown }, evidence: { status: number; body: unknown }) {
  const json = (status: number, b: unknown) =>
    new Response(JSON.stringify(b), { status, headers: { 'content-type': 'application/json' } })
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/api/auth/session')) {
      return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    }
    if (url.includes('/evidence')) return json(evidence.status, evidence.body)
    if (url.includes('/applications/')) return json(app.status, app.body)
    return json(200, {})
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('origination graph (derived from Kotlin, never hand-written)', () => {
  it('was generated with real content', () => {
    // A generator whose regex matched nothing would emit an empty graph and the console would draw
    // "this application has no lifecycle" — a broken probe reporting clean.
    expect(ORIGINATION_GRAPH.states.length).toBeGreaterThan(5)
    expect(ORIGINATION_GRAPH.terminal.length).toBeGreaterThan(0)
    expect(Object.keys(ORIGINATION_GRAPH.edges).length).toBeGreaterThan(0)
  })

  it('every state has a business label', () => {
    const missing = ORIGINATION_GRAPH.states.filter(s => !STATE_LABELS[s])
    // If this fails, a state was added in OriginationState.kt and the console would show a raw
    // enum name to someone approving loans. Add the label; do not weaken the assertion.
    expect(missing).toEqual([])
  })

  it('the derived happy path covers every non-terminal state', () => {
    const spine = new Set(happyPath())
    const uncovered = ORIGINATION_GRAPH.states
      .filter(s => !ORIGINATION_GRAPH.terminal.includes(s))
      .filter(s => !spine.has(s))
    // A state reachable only off the first-edge chain would be invisible on the diagram while the
    // machine can still enter it — the exact silent divergence this screen exists to prevent.
    expect(uncovered).toEqual([])
  })

  it('separates early exits from the happy ending', () => {
    expect(happyPath()).toContain('DISBURSED')
    expect(exitStates()).toEqual(expect.arrayContaining(['WITHDRAWN', 'DECLINED', 'EXPIRED']))
    expect(exitStates()).not.toContain('DISBURSED')
  })
})

describe('OriginationFlow rendering', () => {
  it('marks visited, current and future states differently', () => {
    render(React.createElement(Providers, null,
      React.createElement(OriginationFlow, {
        current: 'FOUR_EYES',
        history: [{ state: 'SUBMITTED', at: '2026-08-01T09:00:00Z', actor: 'app@customer' }],
        lang: 'en',
      })))

    expect(screen.getByTestId('node-SUBMITTED').getAttribute('data-tone')).toBe('done')
    expect(screen.getByTestId('node-FOUR_EYES').getAttribute('data-tone')).toBe('current')
    expect(screen.getByTestId('node-READY_TO_DISBURSE').getAttribute('data-tone')).toBe('future')
  })

  it('a stopped application does not show the rest of the path as still coming', () => {
    render(React.createElement(Providers, null,
      React.createElement(OriginationFlow, { current: 'DECLINED', history: [], lang: 'en' })))

    expect(screen.getByTestId('node-DECLINED').getAttribute('data-tone')).toBe('stopped')
    // Telling an operator to wait for READY_TO_DISBURSE on a declined application is worse than
    // showing nothing — it invents work that will never arrive.
    expect(screen.getByTestId('node-READY_TO_DISBURSE').getAttribute('data-tone')).toBe('future')
  })
})

describe('application flow page', () => {
  const params = Promise.resolve({ id: APP_ID })

  it('draws the real path and the actors behind each step', async () => {
    vi.stubGlobal('fetch', mockFetch({ status: 200, body: APPLICATION }, { status: 200, body: EVIDENCE }))
    await act(async () => { render(React.createElement(Providers, null, React.createElement(ApplicationFlowPage, { params }))) })

    await waitFor(() => expect(screen.getByTestId('origination-flow')).toBeTruthy())
    expect(screen.getByTestId('node-KYC_PENDING').getAttribute('data-tone')).toBe('done')
    // The evidence trail is the answer to "who moved this and why" — the audit question.
    expect(screen.getAllByText('risk@openbank.local').length).toBeGreaterThan(0)
    expect(screen.getAllByText('SYSTEM').length).toBeGreaterThan(0)
  })

  it('a restricted evidence read is stated, never rendered as "no history"', async () => {
    vi.stubGlobal('fetch', mockFetch(
      { status: 200, body: APPLICATION },
      { status: 403, body: { error: 'forbidden' } },
    ))
    await act(async () => { render(React.createElement(Providers, null, React.createElement(ApplicationFlowPage, { params }))) })

    await waitFor(() => expect(screen.getByTestId('evidence-restricted')).toBeTruthy())
    expect(screen.queryByTestId('evidence-empty')).toBeNull()
    expect(screen.getByTestId('evidence-restricted').textContent).toMatch(/does NOT mean/i)
  })

  it('shows decision and disbursement as disabled with the reason, not hidden', async () => {
    vi.stubGlobal('fetch', mockFetch({ status: 200, body: APPLICATION }, { status: 200, body: EVIDENCE }))
    await act(async () => { render(React.createElement(Providers, null, React.createElement(ApplicationFlowPage, { params }))) })

    await waitFor(() => expect(screen.getByTestId('decide-disabled')).toBeTruthy())
    // Hiding them would teach an operator the platform cannot do it; disabling with a reason
    // teaches them where the control actually lives (ADR-0227 D4).
    expect(screen.getByTestId('decide-disabled').hasAttribute('disabled')).toBe(true)
    expect(screen.getByTestId('disburse-disabled').hasAttribute('disabled')).toBe(true)
    // Anchored on the LINK, not on prose — the subtitle also names the inbox, and a whole-page
    // text match would pass on the explanation alone while the way there had been removed.
    const link = screen.getByRole('link', { name: /open the inbox/i })
    expect(link.getAttribute('href')).toBe('/approvals')
  })
})
