// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// LanguageProvider renders English in tests, so every label looked up here is the English half of
// the `t(cs, en)` pair. The Czech half is pinned separately by business-onboarding-a11y.guard.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BusinessOnboardingPage from '@/app/business-onboarding/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => children,
  Can: ({ children }: { children: ReactNode }) => children,
}))

const reviewCase = {
  id: '019fbe12-1111-7222-8333-444444444444',
  status: 'MANUAL_REVIEW',
  scheme: 'CZ_ICO',
  identifier: '45274649',
  requiredSignatures: null,
  requiredSignerRoles: [],
  signedCount: 0,
  extract: {
    legalName: 'Příklad s.r.o.',
    identifier: '45274649',
    scheme: 'CZ_ICO',
    representatives: [
      { fullName: 'Jana Nováková', role: 'předseda představenstva', body: 'představenstvo' },
      { fullName: 'Petr Svoboda', role: 'člen představenstva', body: 'představenstvo' },
    ],
    representationRule: { mode: 'JOINT_N', requiredSigners: 2, sourceText: 'dva jednatelé společně', requiredRoles: [] },
  },
  reviewReason: 'representation rule awaits confirmation — parser suggests JOINT_N / 2 signature(s): dva jednatelé společně',
  createdAt: '2026-09-11T09:00:00Z',
  updatedAt: '2026-09-11T09:00:00Z',
}

const unattested = {
  state: 'UNATTESTED',
  ruleText: 'dva jednatelé společně',
  ruleTextHash: 'a'.repeat(64),
  parserSuggestsSigners: 2,
  parserSuggestsRoles: [],
  parserMode: 'JOINT_N',
  attestation: null,
  previous: null,
}

const superseded = {
  state: 'SUPERSEDED',
  ruleText: 'jednají vždy tři jednatelé společně',
  ruleTextHash: 'b'.repeat(64),
  parserSuggestsSigners: 3,
  parserSuggestsRoles: [],
  parserMode: 'JOINT_N',
  attestation: null,
  previous: {
    id: 'p1', scheme: 'CZ_ICO', identifier: '45274649',
    ruleTextHash: 'a'.repeat(64), ruleText: 'dva jednatelé společně',
    parsedMode: 'JOINT_N', parsedSigners: 2,
    confirmedSigners: 2, confirmedRoles: [],
    attestedBy: 'operator-anna', attestedAt: '2026-09-01T08:00:00Z',
    supersededAt: null, note: null,
  },
}

function mockFetch(decision: unknown, onPost?: (body: string) => Response) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      return onPost ? onPost(String(init.body)) : new Response('{}', { status: 200 })
    }
    if (String(url).includes('/representation/')) {
      return new Response(JSON.stringify(decision), { status: 200, headers: { 'content-type': 'application/json' } })
    }
    return new Response(JSON.stringify([reviewCase]), { status: 200, headers: { 'content-type': 'application/json' } })
  })
}

const renderPage = () => render(<LanguageProvider><BusinessOnboardingPage /></LanguageProvider>)

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('business onboarding representation review', () => {
  it('shows the parser as a SUGGESTION and says the confirmation is what binds', async () => {
    vi.stubGlobal('fetch', mockFetch(unattested))
    renderPage()

    await waitFor(() => expect(screen.getByText('Příklad s.r.o.')).toBeTruthy())
    // Wait for the PANEL, not just the case card: the card renders from the queue response and
    // the representation decision is a second fetch. Asserting before it lands passes against a
    // page that never renders the rule at all.
    await waitFor(() => expect(screen.getByLabelText('Confirm the representation rule')).toBeTruthy())

    // The register's own wording has to be on screen — the operator is confirming THAT text.
    expect(screen.getAllByText(/dva jednatelé společně/).length).toBeGreaterThan(0)
    // The suggestion line is assembled from several text nodes, so match the rendered document
    // rather than a single element.
    const body = document.body.textContent ?? ''
    expect(body).toMatch(/Register wording/)
    expect(body).toMatch(/Parser suggestion/)
    expect(body).toMatch(/a suggestion only; your confirmation is what binds/)
  })

  it('sends the hash of the text it rendered, so a rule that moved cannot be confirmed unseen', async () => {
    const posted: string[] = []
    vi.stubGlobal('fetch', mockFetch(unattested, body => { posted.push(body); return new Response('{}', { status: 200 }) }))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Confirm the representation rule')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('Confirm the representation rule'))

    await waitFor(() => expect(posted.length).toBe(1))
    const sent = JSON.parse(posted[0])
    expect(sent.ruleTextHash).toBe('a'.repeat(64))
    expect(sent.confirmedSigners).toBe(2)
  })

  it('renders a 409 as "the register text changed", never as a generic failure', async () => {
    vi.stubGlobal('fetch', mockFetch(unattested, () => new Response('{}', { status: 409 })))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Confirm the representation rule')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('Confirm the representation rule'))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/changed/))
  })

  it('tells the reviewer a previously confirmed rule CHANGED, quoting what it used to say', async () => {
    // The amendment case. A blank form here would be re-confirmed from memory of a company the
    // reviewer recognises — which is the failure the whole control exists to prevent.
    vi.stubGlobal('fetch', mockFetch(superseded))
    renderPage()

    await waitFor(() => expect(screen.getByText(/The rule has CHANGED/)).toBeTruthy())
    const alert = screen.getAllByRole('alert').map(n => n.textContent).join(' ')
    expect(alert).toMatch(/must not be reused/)
    expect(alert).toMatch(/operator-anna/)
    expect(alert).toMatch(/dva jednatelé společně/)
  })

  it('warns that naming offices sends the signatories to manual review', async () => {
    vi.stubGlobal('fetch', mockFetch(unattested))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Offices that must sign')).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Offices that must sign'), { target: { value: 'predseda, clen' } })

    await waitFor(() => expect(screen.getByText(/each office must be filled by a different person/)).toBeTruthy())
  })

  it('refuses a signature count below one without calling the service', async () => {
    const posted: string[] = []
    vi.stubGlobal('fetch', mockFetch(unattested, body => { posted.push(body); return new Response('{}', { status: 200 }) }))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Required signature count')).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Required signature count'), { target: { value: '0' } })
    fireEvent.click(screen.getByLabelText('Confirm the representation rule'))

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    expect(posted.length).toBe(0)
  })

  it('re-confirming an ATTESTED entity keeps the HUMAN numbers, never reverting to the parser\'s', async () => {
    // The defect this pins: for an ATTESTED decision the server sends the PARSER's reading in
    // parserSuggests* (parsedSigners, and parserSuggestsRoles always empty). Seeding the form from
    // those would open "Re-confirm" pre-filled with 1 and no offices for an entity a human
    // confirmed as 2 signatures from named offices — and one click would supersede a
    // role-constrained joint rule with a bare count of one.
    const attested = {
      state: 'ATTESTED',
      ruleText: 'předseda představenstva spolu s jedním členem představenstva',
      ruleTextHash: 'c'.repeat(64),
      parserSuggestsSigners: 1,
      parserSuggestsRoles: [],
      parserMode: 'SOLE',
      previous: null,
      attestation: {
        id: 'a1', scheme: 'CZ_ICO', identifier: '45274649',
        ruleTextHash: 'c'.repeat(64),
        ruleText: 'předseda představenstva spolu s jedním členem představenstva',
        parsedMode: 'SOLE', parsedSigners: 1,
        confirmedSigners: 2, confirmedRoles: ['predseda', 'clen'],
        attestedBy: 'operator-anna', attestedAt: '2026-09-01T08:00:00Z',
        supersededAt: null, note: null,
      },
    }
    const posted: string[] = []
    vi.stubGlobal('fetch', mockFetch(attested, body => { posted.push(body); return new Response('{}', { status: 200 }) }))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Required signature count')).toBeTruthy())
    expect((screen.getByLabelText('Required signature count') as HTMLInputElement).value).toBe('2')
    expect((screen.getByLabelText('Offices that must sign') as HTMLInputElement).value).toBe('predseda, clen')

    fireEvent.click(screen.getByLabelText('Confirm the representation rule'))
    await waitFor(() => expect(posted.length).toBe(1))
    const sent = JSON.parse(posted[0])
    expect(sent.confirmedSigners).toBe(2)
    expect(sent.confirmedRoles).toEqual(['predseda', 'clen'])
  })

  it('refreshes the panel after a 409 instead of leaving the operator re-posting a dead hash', async () => {
    // The message says "read the new wording". Without a refresh there is no way to: `load` is
    // keyed on scheme+identifier, so nothing re-runs and every further click re-posts a hash that
    // can never match again.
    let decisionFetches = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return new Response('{}', { status: 409 })
      if (String(url).includes('/representation/')) {
        decisionFetches += 1
        return new Response(JSON.stringify(unattested), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify([reviewCase]), { status: 200, headers: { 'content-type': 'application/json' } })
    }))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Confirm the representation rule')).toBeTruthy())
    const before = decisionFetches
    fireEvent.click(screen.getByLabelText('Confirm the representation rule'))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/changed/))
    await waitFor(() => expect(decisionFetches).toBeGreaterThan(before))
  })

  it('says the history could not be loaded rather than looking like a dead button', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('/history')) return new Response('{}', { status: 500 })
      if (String(url).includes('/representation/')) {
        return new Response(JSON.stringify(unattested), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify([reviewCase]), { status: 200, headers: { 'content-type': 'application/json' } })
    }))
    renderPage()

    await waitFor(() => expect(screen.getByLabelText('Show the confirmation history')).toBeTruthy())
    fireEvent.click(screen.getByLabelText('Show the confirmation history'))

    await waitFor(() => expect(document.body.textContent).toMatch(/could not be loaded/))
  })

  it('asks for a queue page large enough that the count label is not a lie', async () => {
    const urls: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      urls.push(String(url))
      if (String(url).includes('/representation/')) {
        return new Response(JSON.stringify(unattested), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify([reviewCase]), { status: 200, headers: { 'content-type': 'application/json' } })
    }))
    renderPage()

    // The service defaults to size=20; the label counts the array it got back, so an unspecified
    // size made "N cases awaiting review" a statement about the page rather than the queue.
    await waitFor(() => expect(urls.some(u => u.includes('/kyb/cases'))).toBe(true))
    const queueUrl = urls.find(u => u.includes('/kyb/cases'))!
    expect(queueUrl).toMatch(/size=\d+/)
    expect(Number(new URL(queueUrl, 'http://x').searchParams.get('size'))).toBeGreaterThan(20)
  })
})
