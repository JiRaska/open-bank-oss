// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0315 D4: a ROLE_TREASURY_SENIOR_APPROVER overrides a counterparty-limit breach on a
// PENDING_APPROVAL deal, with a mandatory reason (POST /deals/{id}/override-limit). Covers the
// model-level visibility rule (role x state x breach x already-overridden) and the deal detail
// page's button, request payload and server-refusal rendering.
import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { dealActions } from '@/components/treasury/model'
import { hasPermission, ROLES } from '@/lib/auth/roles'

const session = vi.hoisted(() => ({ roles: ['ROLE_TREASURY_SENIOR_APPROVER'] as string[], username: 'petr.senior' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Petr', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

import TreasuryDealDetailPage from '@/app/treasury/deals/[id]/page'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const BREACHED_LIMIT = { currency: 'CZK', limit: 5000000, exposureBefore: 4000000, exposureAfter: 6000000, headroomAfter: -1000000, breached: true }
const OK_LIMIT = { currency: 'CZK', limit: 5000000, exposureBefore: 0, exposureAfter: 1000000, headroomAfter: 4000000, breached: false }

const deal = (overrides: Record<string, unknown> = {}) => ({
  dealId: 'd-breach', product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 6000000, rate: 4.25,
  dayCount: 'ACT/360', days: 1, interest: 708.33, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
  state: 'PENDING_APPROVAL', createdBy: 'dana.dealer', createdByType: 'HUMAN', submittedBy: 'dana.dealer', approvedBy: null,
  rationale: null, limitCheck: BREACHED_LIMIT, limitOverride: null,
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:01:00Z',
  history: [{ from: null, to: 'DRAFT', actor: 'dana.dealer', actorType: 'HUMAN', at: '2026-09-25T08:00:00Z', note: null }],
  journals: [],
  ...overrides,
})

const COUNTERPARTIES = [
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 6000000, headroom: -1000000 },
]

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_TREASURY_SENIOR_APPROVER']
  session.username = 'petr.senior'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const overrideButton = () => screen.queryByRole('button', { name: /^(Překročit limit|Override limit)$/ })

const paramsFor = (id: string) => Promise.resolve({ id })

describe('dealActions — overrideLimit visibility', () => {
  const senior = ['ROLE_TREASURY_SENIOR_APPROVER']
  const approverOnly = ['ROLE_TREASURY_APPROVER']
  const breached = { state: 'PENDING_APPROVAL' as const, createdBy: 'dana', submittedBy: 'dana', limitCheck: BREACHED_LIMIT, limitOverride: null }

  it('is granted only to ROLE_TREASURY_SENIOR_APPROVER, never plain approver/dealer/admin', () => {
    expect(hasPermission(senior, 'treasury:deal:override-limit')).toBe(true)
    expect(hasPermission(approverOnly, 'treasury:deal:override-limit')).toBe(false)
    expect(hasPermission(['ROLE_TREASURY_DEALER'], 'treasury:deal:override-limit')).toBe(false)
    expect(hasPermission([ROLES.ADMIN], 'treasury:deal:override-limit')).toBe(false)
  })

  it('shows the action only on a PENDING_APPROVAL, breached deal', () => {
    expect(dealActions(breached, 'petr', senior).overrideLimit).toBe(true)
    expect(dealActions({ ...breached, state: 'DRAFT' }, 'petr', senior).overrideLimit).toBe(false)
    expect(dealActions({ ...breached, state: 'BOOKED' }, 'petr', senior).overrideLimit).toBe(false)
    expect(dealActions({ ...breached, limitCheck: OK_LIMIT }, 'petr', senior).overrideLimit).toBe(false)
    expect(dealActions({ ...breached, limitCheck: null }, 'petr', senior).overrideLimit).toBe(false)
  })

  it('hides the action once a limit override is already recorded', () => {
    const already = { ...breached, limitOverride: { by: 'x', reason: 'r', at: '2026-01-01T00:00:00Z', coversExposureUpTo: 1, limitAtOverride: 1 } }
    expect(dealActions(already, 'petr', senior).overrideLimit).toBe(false)
  })

  it('withholds it from a plain approver or dealer even on a breached pending deal', () => {
    expect(dealActions(breached, 'petr', approverOnly).overrideLimit).toBe(false)
    expect(dealActions(breached, 'petr', ['ROLE_TREASURY_DEALER']).overrideLimit).toBe(false)
  })

  it('four-eyes courtesy: withheld from the deal’s own creator/submitter', () => {
    expect(dealActions(breached, 'dana', senior).overrideLimit).toBe(false)
  })
})

describe('deal detail page — senior limit override action', () => {
  it('renders the button for a senior approver on a breached pending deal, not for a plain approver', async () => {
    router = url => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/deals/d-breach')) return json(deal())
      return json({}, 404)
    }
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    expect(await screen.findByText(/Překročen|Breached/)).toBeTruthy()
    expect(overrideButton()).toBeTruthy()

    cleanup()
    session.roles = ['ROLE_TREASURY_APPROVER']
    session.username = 'petr.approver'
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    await screen.findByText(/Překročen|Breached/)
    expect(overrideButton()).toBeNull()
  })

  it('hides the button when the deal is not breached, or already overridden', async () => {
    router = url => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/deals/d-breach')) return json(deal({ limitCheck: OK_LIMIT }))
      return json({}, 404)
    }
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    await screen.findByText(/V limitu|Within limit/)
    expect(overrideButton()).toBeNull()

    cleanup()
    router = url => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/deals/d-breach')) {
        return json(deal({ limitOverride: { by: 'other.senior', reason: 'desk head approved', at: '2026-09-25T09:00:00Z', coversExposureUpTo: 6000000, limitAtOverride: 5000000 } }))
      }
      return json({}, 404)
    }
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    await screen.findByText(/other.senior/)
    expect(overrideButton()).toBeNull()
  })

  it('disables the button until a reason is typed, then posts {reason} with an idempotency key', async () => {
    let overridden: Record<string, unknown> | null = null
    router = url => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/deals/d-breach/override-limit')) {
        overridden = { limitOverride: { by: 'petr.senior', reason: 'desk head approved', at: '2026-09-25T09:00:00Z', coversExposureUpTo: 6000000, limitAtOverride: 5000000 } }
        return json(deal(overridden))
      }
      if (url.endsWith('/deals/d-breach')) return json(deal(overridden ?? {}))
      return json({}, 404)
    }
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    const btn = await screen.findByRole('button', { name: /^(Překročit limit|Override limit)$/ })
    expect(btn).toBeDisabled()

    fireEvent.change(screen.getByPlaceholderText(/Důvod \(povinný\)|Reason \(required\)/), { target: { value: 'desk head approved' } })
    expect(btn).not.toBeDisabled()
    await act(async () => { fireEvent.click(btn) })

    const post = calls.find(c => c.url.endsWith('/deals/d-breach/override-limit'))!
    expect(post).toBeTruthy()
    expect(JSON.parse(String(post.init!.body))).toEqual({ reason: 'desk head approved' })
    expect(new Headers(post.init!.headers).get('idempotency-key')).toMatch(/^[0-9a-f-]{36}$/)
    expect(post.init?.method).toBe('POST')

    expect(await screen.findByText(/petr.senior/)).toBeTruthy()
    expect((await screen.findByRole('status')).textContent).toMatch(/Override limit: done|Překročit limit: hotovo/)
  })

  it('renders the server’s LIMIT_BREACHED / INVALID_STATE / FOUR_EYES_VIOLATION refusal readably', async () => {
    router = url => {
      if (url.includes('/counterparties')) return json(COUNTERPARTIES)
      if (url.endsWith('/deals/d-breach') && !url.includes('override-limit')) return json(deal())
      if (url.endsWith('/deals/d-breach/override-limit')) {
        return json({ error: 'FOUR_EYES_VIOLATION', message: "four-eyes: the deal's creator must not override its limit" }, 422)
      }
      return json({}, 404)
    }
    await renderPage(<TreasuryDealDetailPage params={paramsFor('d-breach')} />)
    const btn = await screen.findByRole('button', { name: /^(Překročit limit|Override limit)$/ })
    fireEvent.change(screen.getByPlaceholderText(/Důvod \(povinný\)|Reason \(required\)/), { target: { value: 'x' } })
    await act(async () => { fireEvent.click(btn) })
    const notice = await screen.findByRole('status')
    expect(notice.textContent).toMatch(/four-eyes|čtyř očí/)
    expect(notice.textContent).toMatch(/creator must not override/)
    expect(notice.textContent).not.toMatch(/422/)
  })
})
