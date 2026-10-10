// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Operator screens for pension-service API 1.2.0: annuity partner registry (four-eyes), state
// contribution, the application view and the participant-change panel on a contract.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_OPERATOR'] as string[], username: 'olga.operator' }))
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Olga', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => '/pension' }))

import PensionAnnuityPage from '@/app/pension/annuity/page'
import PensionIncentiveBatchesPage from '@/app/pension/incentives/page'
import PensionMandatesPage from '@/app/pension/mandates/page'
import PensionApplicationPage from '@/app/pension/applications/[id]/page'
import { ContractChanges } from '@/components/pension/ContractChanges'
import { canApproveAnnuityProvider, canRequestAnnuityActivation } from '@/components/pension/model'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const APP_ID = '88888888-8888-4888-8888-888888888888'
const CONTRACT_ID = '44444444-4444-4444-8444-444444444444'

const provider = (partnerId: string, status: string, proposedBy: string | null, activationRequestedBy: string | null) => ({
  partnerId, status, liveVersion: null, liveTerms: null, approvedBy: null, approvedAt: null, proposedVersion: 1,
  proposedTerms: { legalName: `${partnerId} pojišťovna` }, proposedBy, activationRequestedBy, updatedAt: '2026-10-09T10:00:00Z',
})

const renderPage = async (node: React.ReactNode) => {
  await act(async () => { render(<LanguageProvider><Suspense fallback={null}>{node}</Suspense></LanguageProvider>) })
}

let calls: { url: string; init?: RequestInit }[] = []
let router: (url: string, init?: RequestInit) => Response

beforeEach(() => {
  calls = []
  session.roles = ['ROLE_OPERATOR']
  session.username = 'olga.operator'
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('annuity partner four-eyes', () => {
  it('lets neither the editor nor the activation requester approve', () => {
    const p = provider('acme', 'PENDING_ACTIVATION', 'karel.operator', 'jana.operator')
    expect(canApproveAnnuityProvider(p, 'olga.operator')).toBe(true)
    expect(canApproveAnnuityProvider(p, 'karel.operator')).toBe(false)
    expect(canApproveAnnuityProvider(p, 'jana.operator')).toBe(false)
    expect(canApproveAnnuityProvider(p, null)).toBe(false)
    expect(canApproveAnnuityProvider({ ...p, status: 'DRAFT' }, 'olga.operator')).toBe(false)
    expect(canRequestAnnuityActivation({ ...p, status: 'DRAFT' })).toBe(true)
    expect(canRequestAnnuityActivation({ ...p, status: 'DRAFT', proposedTerms: null })).toBe(false)
  })

  it('offers approval on someone else’s request, withholds it on the viewer’s own, and posts with an idempotency key', async () => {
    router = (url, init) => {
      if (url.includes('/operator/annuity-providers/other/activation-approval') && init?.method === 'POST') {
        return json(provider('other', 'ACTIVE', 'karel.operator', 'jana.operator'))
      }
      if (url.includes('/operator/annuity-providers')) {
        return json([
          provider('own', 'PENDING_ACTIVATION', 'karel.operator', 'olga.operator'),
          provider('other', 'PENDING_ACTIVATION', 'karel.operator', 'jana.operator'),
        ])
      }
      if (url.includes('/operator/annuity-purchases')) return json([])
      return json({}, 404)
    }
    await renderPage(<PensionAnnuityPage />)
    const approve = screen.getAllByRole('button', { name: 'Approve activation' })
    expect(approve).toHaveLength(1)
    expect(screen.getByText('Another operator approves')).toBeTruthy()
    await act(async () => { fireEvent.click(approve[0]) })
    const post = calls.find(c => c.init?.method === 'POST')
    expect(post?.url).toContain('/api/svc/pension-service/api/v2/pension/operator/annuity-providers/other/activation-approval')
    expect((post?.init?.headers as Record<string, string>)['idempotency-key']).toMatch(/[0-9a-f-]{36}/)
  })

  it('renders a refused self-approval readably', async () => {
    router = (url, init) => {
      if (init?.method === 'POST') return json({ error: 'approver must differ from the maker' }, 403)
      if (url.includes('/operator/annuity-providers')) return json([provider('other', 'PENDING_ACTIVATION', 'karel.operator', 'jana.operator')])
      return json([])
    }
    await renderPage(<PensionAnnuityPage />)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Approve activation' })) })
    expect(screen.getByRole('status').textContent).toMatch(/not permitted/)
  })

  it('validates a registration before any call and hides writes from an auditor', async () => {
    router = url => (url.includes('/operator/annuity-providers') ? json([]) : json([]))
    await renderPage(<PensionAnnuityPage />)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Register as draft' })) })
    expect(screen.getByRole('alert').textContent).toMatch(/Partner id/)
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false)
    cleanup()
    session.roles = ['ROLE_AUDITOR']
    await renderPage(<PensionAnnuityPage />)
    expect(screen.queryByRole('button', { name: 'Register as draft' })).toBeNull()
  })
})

describe('state contribution', () => {
  it('shows the deadline counters and reads returns by status', async () => {
    router = url => {
      if (url.includes('/state-contribution/deadlines')) return json({ claimsPastFilingDeadline: 2, claimsPastExpectedPayment: 0, returnsOverdue: 1 })
      if (url.includes('/state-contribution/returns')) return json([{ id: 'ret-1', contractId: CONTRACT_ID, cause: 'INELIGIBLE', amount: 230, currency: 'CZK', status: 'DUE', dueBy: '2026-11-30' }])
      return json([])
    }
    await renderPage(<PensionIncentiveBatchesPage />)
    expect(screen.getByText('Claims past the filing deadline')).toBeTruthy()
    expect(screen.getByText('ret-1')).toBeTruthy()
    expect(calls.some(c => c.url.includes('/state-contribution/returns') && c.url.includes('status=DUE'))).toBe(true)
  })
})

describe('contribution mandates', () => {
  it('reads the staff mandates list by status and links each row to its contract', async () => {
    const mandate = (id: string, status: string) => ({
      id, contractId: CONTRACT_ID, kind: 'STANDING_ORDER', externalId: `SO-${id}`, status,
      createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-02T10:00:00Z',
    })
    router = url => {
      if (url.includes('/operator/mandates') && url.includes('status=ACTIVE')) return json([mandate('m-active', 'ACTIVE')])
      if (url.includes('/operator/mandates') && url.includes('status=CANCELLED')) return json([mandate('m-gone', 'CANCELLED')])
      return json([], 404)
    }
    await renderPage(<PensionMandatesPage />)
    expect(screen.getByText('SO-m-active')).toBeTruthy()
    expect(screen.getByText('SO-m-gone')).toBeTruthy()
    const mandateCalls = calls.filter(c => c.url.includes('/api/v2/pension/operator/mandates'))
    expect(mandateCalls).toHaveLength(2)
    expect(mandateCalls.every(c => (c.init?.method ?? 'GET') === 'GET')).toBe(true)
    const links = screen.getAllByRole('link').map(a => a.getAttribute('href'))
    expect(links).toContain(`/pension/contracts/${CONTRACT_ID}`)
  })
})

describe('application view', () => {
  it('shows the assessment trail and links the contract', async () => {
    router = url => (url.includes(`/operator/onboarding/applications/${APP_ID}`)
      ? json({ partyId: 'p-1', application: { applicationId: APP_ID, status: 'SIGNED', recommendedStrategy: 'LIFECYCLE', chosenStrategy: 'DYNAMIC', unsuitableChoiceAcknowledged: true, contractId: CONTRACT_ID } })
      : json({}, 404))
    await renderPage(<PensionApplicationPage params={Promise.resolve({ id: APP_ID })} />)
    expect(screen.getByText('LIFECYCLE')).toBeTruthy()
    expect(screen.getByText('DYNAMIC')).toBeTruthy()
    expect(screen.getByText('yes')).toBeTruthy()
    expect(screen.getByRole('link', { name: `Contract ${CONTRACT_ID}` }).getAttribute('href')).toBe(`/pension/contracts/${CONTRACT_ID}`)
  })

  it('refuses a malformed id without a call', async () => {
    router = () => json({})
    await renderPage(<PensionApplicationPage params={Promise.resolve({ id: 'nope' })} />)
    expect(calls).toHaveLength(0)
  })
})

describe('participant changes on a contract', () => {
  it('reads the staff schedule and beneficiary routes, never a write', async () => {
    router = url => {
      if (url.includes('/contribution-schedule')) return json({ inForce: { amount: 1000, currency: 'CZK', frequency: 'MONTHLY', dayOfMonth: 15, effectiveFrom: '2026-01-15' }, pending: null, original: null, history: [] })
      if (url.includes('/beneficiaries')) return json({ current: [{ name: 'Jana', sharePercent: 100 }], history: [] })
      return json({}, 404)
    }
    await renderPage(<ContractChanges contractId={CONTRACT_ID} />)
    expect(calls.map(c => c.url).every(u => u.includes('/api/svc/pension-service/api/v2/pension/operator/contracts/'))).toBe(true)
    expect(document.body.textContent).toMatch(/1000 CZK · MONTHLY · day 15/)
    expect(document.body.textContent).toMatch(/Jana 100 %/)
    expect(calls.some(c => c.init?.method)).toBe(false)
  })
})
