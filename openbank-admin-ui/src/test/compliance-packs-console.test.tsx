// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Behaviour tests for the ADR-0212 D4 compliance-pack activation console.
//
// Every case here is one the naive implementation gets WRONG, and each was run against a
// deliberately-broken version first:
//  - a 403 on the pending list rendering as "Nothing pending" (the worst thing an approvals
//    screen can say),
//  - the 422 maker-checker refusal flattened to a generic "Decision failed", which hides the
//    single fact the operator needs (the SAME person cannot do both halves),
//  - an empty active list rendered as unremarkable, when it is precisely the state in which
//    LENDING_ENFORCE_PACK must not be flipped.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, it, expect, afterEach, vi } from 'vitest'
import React from 'react'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { SessionProvider } from 'next-auth/react'
import CompliancePacksPage from '@/app/lending/compliance-packs/page'

const PROPOSAL_ID = '019fb939-3e0a-7716-a1ed-7854754c8786'

const PENDING = [{
  id: PROPOSAL_ID,
  jurisdiction: 'CZ',
  productType: 'CONSUMER_CREDIT',
  packVersion: 1,
  effectiveFrom: '2026-01-01',
  contentHash: 'b7c4d1e9a0f35286bb1122334455667788990011223344556677889900aabbcc',
  state: 'PROPOSED',
  proposedBy: 'maker@openbank.local',
  decidedBy: null,
  decidedAt: null,
  proposedAt: '2026-08-20T08:00:00Z',
  decisionReason: null,
  pack: { jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', version: 1, coolingOffDays: 14 },
}]

const ACTIVE = [{ ...PENDING[0], id: '00000000-0000-0000-0000-000000000000', state: 'EXECUTED', proposedBy: '-' }]

function Providers({ children }: { children: React.ReactNode }) {
  return React.createElement(
    SessionProvider,
    { session: { user: { roles: ['ROLE_COMPLIANCE'], email: 'compliance@openbank.local' } } as never },
    React.createElement(LanguageProvider, null, children),
  )
}

type Case = { active?: { status: number; body: unknown }; pending?: { status: number; body: unknown }; decide?: { status: number; body: unknown } }

function mockFetch(c: Case) {
  const json = (status: number, b: unknown) =>
    new Response(JSON.stringify(b), { status, headers: { 'content-type': 'application/json' } })
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.includes('/api/auth/session')) {
      return new Response('null', { status: 200, headers: { 'content-type': 'application/json' } })
    }
    if (url.includes('/decide')) {
      const d = c.decide ?? { status: 200, body: {} }
      return json(d.status, d.body)
    }
    if (url.includes('/proposals/pending')) {
      const p = c.pending ?? { status: 200, body: [] }
      return json(p.status, p.body)
    }
    if (url.includes('/compliance-packs/active')) {
      const a = c.active ?? { status: 200, body: [] }
      return json(a.status, a.body)
    }
    if (url.includes('/proposals')) return json(201, PENDING[0])
    return json(200, {})
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('compliance pack activation console', () => {
  it('uses the shared PageHeader (ADR-0212 maker-checker console consolidation)', () => {
    const source = readFileSync(
      path.resolve(__dirname, '../app/lending/compliance-packs/page.tsx'),
      'utf8',
    )
    expect(source).toContain('<PageHeader')
    expect(source).toContain('breadcrumb={<div className="breadcrumb">')
    expect(source).not.toContain('className="page-header"')
    // The maker-checker "acting as" line and the refresh action are content this migration
    // must not lose or silently rename — both are asserted for real DOM presence below, this
    // just pins the source-level wiring so a future header refactor can't drop them quietly.
    expect(source).toContain('data-testid="acting-as"')
    expect(source).toContain("onClick={() => void load()}")
  })

  it('lists the active pack with its jurisdiction, product and content hash', async () => {
    vi.stubGlobal('fetch', mockFetch({ active: { status: 200, body: ACTIVE } }))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByText('CONSUMER_CREDIT')).toBeTruthy())
    expect(screen.getByText('CZ')).toBeTruthy()
    // The hash is what ties an activated pack to a reviewable document. A console that shows
    // "CZ v1 active" without it cannot answer "active *as of which text*".
    expect(screen.getAllByText(/b7c4d1e9a0f35286/).length).toBeGreaterThan(0)
  })

  it('opens the exact reviewed pack and maker-checker audit detail', async () => {
    vi.stubGlobal('fetch', mockFetch({ active: { status: 200, body: ACTIVE } }))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByRole('button', { name: 'View details' })).toBeTruthy())
    fireEvent.click(screen.getByRole('button', { name: 'View details' }))
    expect(screen.getByText('Exact pack content')).toBeInTheDocument()
    expect(screen.getByText(/"coolingOffDays": 14/)).toBeInTheDocument()
    expect(screen.getByText(PENDING[0].contentHash)).toBeInTheDocument()
  })

  it('an empty active list states the enforce-pack consequence, not just "none"', async () => {
    vi.stubGlobal('fetch', mockFetch({}))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    // Wait on the TEXT, not on the node. `no-active` is rendered on the very first pass —
    // `active` starts empty — so waiting for the node to exist succeeds before any fetch has
    // resolved, and the cell still reads "Not loaded.". Written the old way this assertion
    // passed or failed on timing alone, and it started failing the first time the admin-ui
    // suite actually ran on a PR that touched this directory.
    // With enforcement on and no pack, origination is refused fleet-wide. That is the whole
    // reason this screen exists before the flag flip, so it has to be said on the screen.
    await waitFor(() =>
      expect(screen.getByTestId('no-active').textContent).toMatch(/LENDING_ENFORCE_PACK/),
    )
  })

  it('a refused pending read never renders as "nothing pending"', async () => {
    vi.stubGlobal('fetch', mockFetch({ pending: { status: 403, body: { error: 'forbidden' } } }))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByTestId('degraded')).toBeTruthy())
    expect(screen.queryByText('Nothing pending.')).toBeNull()
    expect(screen.getByTestId('degraded').textContent).toMatch(/pending/)
  })

  it('renders the maker-checker refusal verbatim instead of a generic failure', async () => {
    vi.stubGlobal('fetch', mockFetch({
      pending: { status: 200, body: PENDING },
      // What lending-service returns on MakerCheckerViolation (422) — the checker is the maker.
      decide: { status: 422, body: { error: 'Checker maker@openbank.local must differ from maker maker@openbank.local' } },
    }))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByTestId(`proposal-${PROPOSAL_ID}`)).toBeTruthy())
    fireEvent.click(screen.getByText('Approve'))

    await waitFor(() => expect(screen.getByTestId('error')).toBeTruthy())
    expect(screen.getByTestId('error').textContent).toMatch(/must differ from maker/)
  })

  it('keeps decision controls separate from the pack-detail disclosure', async () => {
    vi.stubGlobal('fetch', mockFetch({ pending: { status: 200, body: PENDING } }))
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByTestId(`proposal-${PROPOSAL_ID}`)).toBeTruthy())
    const reason = screen.getByRole('textbox', { name: 'Decision reason' })
    fireEvent.click(reason)
    fireEvent.change(reason, { target: { value: 'independent compliance review' } })

    expect(reason).toHaveValue('independent compliance review')
    expect(screen.queryByText('Exact pack content')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'View details' }))
    expect(screen.getByText('Exact pack content')).toBeInTheDocument()
  })

  it('approving posts approve=true to the proposal decide route', async () => {
    const f = mockFetch({ pending: { status: 200, body: PENDING } })
    vi.stubGlobal('fetch', f)
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByTestId(`proposal-${PROPOSAL_ID}`)).toBeTruthy())
    fireEvent.click(screen.getByText('Approve'))

    await waitFor(() => {
      const call = f.mock.calls.find(([u]) => String(u).includes('/decide'))
      expect(call).toBeTruthy()
      expect(String(call?.[0])).toContain(`/proposals/${PROPOSAL_ID}/decide`)
      expect(JSON.parse(String((call?.[1] as RequestInit)?.body))).toMatchObject({ approve: true })
    })
  })

  it('refuses to propose a pack that is not valid JSON, without calling the service', async () => {
    const f = mockFetch({})
    vi.stubGlobal('fetch', f)
    render(React.createElement(Providers, null, React.createElement(CompliancePacksPage)))

    await waitFor(() => expect(screen.getByTestId('no-active')).toBeTruthy())
    fireEvent.change(screen.getByLabelText('Pack JSON'), { target: { value: '{ not json' } })
    fireEvent.click(screen.getByText('Propose'))

    await waitFor(() => expect(screen.getByTestId('error').textContent).toMatch(/not valid JSON/))
    expect(f.mock.calls.some(([u, i]) => String(u).includes('/proposals') && (i as RequestInit)?.method === 'POST')).toBe(false)
  })
})
