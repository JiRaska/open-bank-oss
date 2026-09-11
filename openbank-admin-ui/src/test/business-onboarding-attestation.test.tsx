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
})
