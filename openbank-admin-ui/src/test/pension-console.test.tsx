// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React, { Suspense } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({ roles: ['ROLE_OPERATOR'] as string[], username: 'olga.operator' }))
const push = vi.hoisted(() => vi.fn())
const jwt = (claims: Record<string, unknown>) => `h.${btoa(JSON.stringify(claims)).replace(/=+$/, '')}.s`

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { id: 'sub-1', name: 'Olga', roles: session.roles, accessToken: jwt({ preferred_username: session.username, sub: 'sub-1' }) } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }), usePathname: () => '/pension' }))

import PensionContractsPage from '@/app/pension/page'
import PensionContractDetailPage from '@/app/pension/contracts/[id]/page'
import PensionFundsPage from '@/app/pension/funds/page'
import PensionStrategiesPage from '@/app/pension/strategies/page'
import PensionPayoutsPage from '@/app/pension/payouts/page'
import PensionQueuesPage from '@/app/pension/queues/page'
import { allocationProblem, canApplyChange, canDecideChange, canDecideNav, contractTimeline } from '@/components/pension/model'
import { hasPermission } from '@/lib/auth/roles'

const en = (_cs: string, english: string) => english
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const CONTRACT_ID = '44444444-4444-4444-8444-444444444444'
const FUND_A = '11111111-1111-4111-8111-111111111111'
const FUND_B = '22222222-2222-4222-8222-222222222222'
const STRATEGY = '33333333-3333-4333-8333-333333333333'

const contract = (status: string) => ({
  contractId: CONTRACT_ID, participantPartyId: '55555555-5555-4555-8555-555555555555', productLine: 'DPS', jurisdiction: 'CZ',
  packVersion: 1, providerEntityId: '66666666-6666-4666-8666-666666666666', providerType: 'PENSION_COMPANY', status,
  schedule: { amount: 1000, currency: 'CZK', frequency: 'MONTHLY' },
  currentStrategy: { strategyCode: 'BALANCED', effectiveFrom: '2026-01-01', electedAt: '2026-01-01T10:00:00Z' },
  strategyHistory: [{ strategyCode: 'BALANCED', effectiveFrom: '2026-01-01', electedAt: '2026-01-01T10:00:00Z' }],
  beneficiaries: [], startDate: null, createdAt: '2026-01-01T09:00:00Z', updatedAt: '2026-01-02T09:00:00Z',
})
const fund = (id: string, name: string) => ({
  id, name, isin: 'CZ0000000001', lei: '5299000000000000AB12', currency: 'CZK', riskClass: 3,
  mandatoryConservative: false, managementFeeRate: 0.008, status: 'ACTIVE',
})
const nav = (id: string, calculatedBy: string, status = 'CALCULATED') => ({
  id, fundId: FUND_A, valuationDate: '2026-10-08', grossAssets: 1000, accruedManagementFee: 1, otherLiabilities: 0,
  netAssets: 999, unitsOutstanding: 900, navPerUnit: 1.11, status, calculatedBy, approvedBy: null, publishedAt: null, correctsNavId: null,
})
const change = (id: string, submittedBy: string, status = 'PENDING_APPROVAL') => ({
  id, strategyId: STRATEGY, proposedAllocations: [{ fundId: FUND_A, weight: 1, lowerBand: 0.9, upperBand: 1 }],
  reason: 'de-risk', effectiveDate: '2026-11-01', submittedBy, submittedAt: '2026-10-01T10:00:00Z', status,
  decidedBy: null, participantNotificationDate: null, appliedAt: null,
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
  push.mockReset()
  vi.stubGlobal('fetch', vi.fn(async (u: string, init?: RequestInit) => { calls.push({ url: String(u), init }); return router(String(u), init) }))
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('pension model', () => {
  it('accepts an allocation that sums to exactly 1 despite floating point', () => {
    expect(allocationProblem([
      { fundId: 'a', weight: 0.1, lowerBand: 0, upperBand: 0.2 },
      { fundId: 'b', weight: 0.2, lowerBand: 0.1, upperBand: 0.3 },
      { fundId: 'c', weight: 0.7, lowerBand: 0.6, upperBand: 0.8 },
    ], en)).toBeNull()
  })

  it('refuses a sum other than 1, a duplicate fund and a weight outside its band', () => {
    expect(allocationProblem([{ fundId: 'a', weight: 0.5, lowerBand: 0, upperBand: 1 }], en)).toMatch(/sum to exactly 1/)
    expect(allocationProblem([
      { fundId: 'a', weight: 0.5, lowerBand: 0, upperBand: 1 },
      { fundId: 'a', weight: 0.5, lowerBand: 0, upperBand: 1 },
    ], en)).toMatch(/only once/)
    expect(allocationProblem([{ fundId: 'a', weight: 1, lowerBand: 0, upperBand: 0.9 }], en)).toMatch(/inside its band/)
  })

  it('offers a decision only to someone other than the maker', () => {
    expect(canDecideNav(nav('n', 'olga.operator'), 'olga.operator')).toBe(false)
    expect(canDecideNav(nav('n', 'karel.operator'), 'olga.operator')).toBe(true)
    expect(canDecideNav(nav('n', 'karel.operator', 'PUBLISHED'), 'olga.operator')).toBe(false)
    expect(canDecideNav(nav('n', 'karel.operator'), null)).toBe(false)
    expect(canDecideChange(change('c', 'olga.operator'), 'olga.operator')).toBe(false)
    expect(canDecideChange(change('c', 'karel.operator'), 'olga.operator')).toBe(true)
  })

  it('applies an approved change only once its effective date has arrived', () => {
    expect(canApplyChange(change('c', 'k', 'APPROVED'), '2026-10-31')).toBe(false)
    expect(canApplyChange(change('c', 'k', 'APPROVED'), '2026-11-01')).toBe(true)
    expect(canApplyChange(change('c', 'k', 'PENDING_APPROVAL'), '2026-12-01')).toBe(false)
  })

  it('orders the contract timeline chronologically', () => {
    const events = contractTimeline({ ...contract('ACTIVE'), strategyHistory: [], beneficiaries: [] } as never, en)
    expect(events.map(e => e.label)).toEqual(['Contract created', 'Current status: Active'])
  })

  it('grants pension writes to operators and admins but not to auditors', () => {
    expect(hasPermission(['ROLE_AUDITOR'], 'pension:view')).toBe(true)
    expect(hasPermission(['ROLE_AUDITOR'], 'pension:operate')).toBe(false)
    expect(hasPermission(['ROLE_OPERATOR'], 'pension:operate')).toBe(true)
  })
})

describe('contract search', () => {
  it('rejects a non-UUID contract id without navigating', async () => {
    router = () => json([])
    await renderPage(<PensionContractsPage />)
    fireEvent.change(screen.getByLabelText('Contract id'), { target: { value: 'not-an-id' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Open' })) })
    expect(push).not.toHaveBeenCalled()
    expect(screen.getByRole('alert').textContent).toMatch(/UUID/)
  })

  it('caps the list request and degrades when the list route is not deployed', async () => {
    router = () => json({ error: 'Unknown service' }, 404)
    await renderPage(<PensionContractsPage />)
    expect(calls[0].url).toContain('/api/svc/pension-service/api/v2/pension/contracts')
    expect(calls[0].url).toMatch(/limit=\d+/)
    expect(document.body.textContent).not.toMatch(/HTTP 404/)
  })
})

describe('contract detail', () => {
  it('shows holdings and offers no activation: a contract activates only through its application', async () => {
    router = url => {
      if (url.includes(`/api/v2/pension/contracts/${CONTRACT_ID}`)) return json(contract('PENDING_ACTIVATION'))
      if (url.includes('/holdings')) return json({ contractId: CONTRACT_ID, holdings: [{ fundId: FUND_A, units: 10, navPerUnit: 1.5, navDate: '2026-10-08', value: 15, currency: 'CZK' }], pendingOrders: [] })
      if (url.includes('/transactions')) return json([])
      return json({}, 404)
    }
    await renderPage(<PensionContractDetailPage params={Promise.resolve({ id: CONTRACT_ID })} />)
    expect(screen.getByText('Lifecycle timeline')).toBeTruthy()
    expect(screen.getByTitle(FUND_A)).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Activate contract' })).toBeNull()
    expect(document.body.textContent).toMatch(/cooling-off/)
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false)
    expect(calls.some(c => c.url.includes('/activate'))).toBe(false)
  })
})

describe('NAV four-eyes', () => {
  it('withholds publish on the viewer’s own NAV and offers it on someone else’s', async () => {
    router = url => {
      if (url.endsWith('/api/v1/funds')) return json([fund(FUND_A, 'Balanced fund')])
      if (url.includes('/navs')) return json([nav('own', 'olga.operator'), nav('other', 'karel.operator')])
      return json({}, 404)
    }
    await renderPage(<PensionFundsPage />)
    expect(screen.getAllByRole('button', { name: 'Publish' })).toHaveLength(1)
    expect(screen.getByText(/Your calculation/)).toBeTruthy()
  })

  it('renders the service’s refusal readably when a publish is refused anyway', async () => {
    router = (url, init) => {
      if (url.includes('/navs/other/approve') && init?.method === 'POST') return json({ error: 'FOUR_EYES_VIOLATION' }, 409)
      if (url.endsWith('/api/v1/funds')) return json([fund(FUND_A, 'Balanced fund')])
      if (url.includes('/navs')) return json([nav('other', 'karel.operator')])
      return json({}, 404)
    }
    await renderPage(<PensionFundsPage />)
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Publish' })) })
    expect(screen.getByRole('status').textContent).toContain('FOUR_EYES_VIOLATION')
  })
})

describe('strategy change four-eyes', () => {
  beforeEach(() => {
    router = (url, init) => {
      if (url.endsWith('/api/v1/funds')) return json([fund(FUND_A, 'Bond fund'), fund(FUND_B, 'Equity fund')])
      if (url.endsWith('/api/v1/strategies')) {
        return json([{ id: STRATEGY, name: 'Balanced', allocations: [{ fundId: FUND_A, weight: 0.5, lowerBand: 0.4, upperBand: 0.6 }, { fundId: FUND_B, weight: 0.5, lowerBand: 0.4, upperBand: 0.6 }], lifecycle: false, status: 'ACTIVE', version: 3 }])
      }
      if (url.endsWith('/changes') && init?.method === 'POST') return json(change('new', 'olga.operator'), 201)
      if (url.endsWith('/changes')) return json([change('own', 'olga.operator'), change('other', 'karel.operator')])
      return json({}, 404)
    }
  })

  it('withholds approval on the viewer’s own proposal', async () => {
    await renderPage(<PensionStrategiesPage />)
    expect(screen.getAllByRole('button', { name: 'Approve' })).toHaveLength(1)
    expect(screen.getByText(/Your proposal/)).toBeTruthy()
  })

  it('refuses an allocation that does not sum to 1 before calling the service', async () => {
    await renderPage(<PensionStrategiesPage />)
    fireEvent.change(screen.getAllByLabelText('Weight')[0], { target: { value: '0.6' } })
    fireEvent.change(screen.getAllByLabelText('Upper band')[0], { target: { value: '0.7' } })
    fireEvent.change(screen.getByLabelText('Reason for the change'), { target: { value: 'tilt' } })
    fireEvent.change(screen.getByLabelText('Effective from'), { target: { value: '2999-01-01' } })
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Propose change' })) })
    expect(calls.some(c => c.init?.method === 'POST')).toBe(false)
    expect(screen.getByRole('alert').textContent).toMatch(/sum to exactly 1/)
  })
})

describe('payouts and death claims', () => {
  it('reads the staff list routes and links each row to its contract', async () => {
    router = url => {
      if (url.includes('/api/v2/pension/operator/payouts')) {
        return json([{ payoutId: 'pay-1', contractId: CONTRACT_ID, form: 'LUMP_SUM', status: 'CONFIRMED', grossAmount: 1000, taxWithheld: 150, netAmount: 850, currency: 'CZK', payoutAccountLast4: '5399', pendingAccountLast4: null }])
      }
      if (url.includes('/api/v2/pension/death-claims')) {
        return json([{ claimId: 'claim-1', contractId: CONTRACT_ID, status: 'NOTIFIED', dateOfDeath: '2026-09-01', notifiedBy: 'olga.operator', approvedBy: null, valuation: 12000, incentiveReturn: 0 }])
      }
      return json({}, 404)
    }
    await renderPage(<PensionPayoutsPage />)
    const urls = calls.map(c => c.url)
    expect(urls.some(u => u.includes('/api/svc/pension-service/api/v2/pension/operator/payouts'))).toBe(true)
    expect(urls.some(u => u.includes('/api/svc/pension-service/api/v2/pension/death-claims'))).toBe(true)
    expect(urls.some(u => /\/api\/v1\/pension\/payouts(\?|$)/.test(u))).toBe(false)
    expect(document.body.textContent).toContain('5399')
    expect(document.body.textContent).toContain('2026-09-01')
    const links = screen.getAllByRole('link').map(l => l.getAttribute('href'))
    expect(links.filter(h => h === `/pension/contracts/${CONTRACT_ID}`).length).toBeGreaterThanOrEqual(2)
  })

  it('degrades to a calm panel while pension-service is not deployed', async () => {
    router = () => json({ error: 'Unknown service' }, 404)
    await renderPage(<PensionPayoutsPage />)
    expect(document.body.textContent).not.toMatch(/HTTP 404|alert-error/)
  })
})

describe('operator queues', () => {
  it('reads the S2 operator routes and renders nested application rows', async () => {
    router = url => {
      if (url.includes('/operator/onboarding/applications')) {
        return json([{ partyId: 'p-1', application: { applicationId: 'app-1', kind: 'NEW', status: 'SIGNED', productLine: 'DPS', chosenStrategy: 'BALANCED', signedAt: '2026-10-01T10:00:00Z' } }])
      }
      if (url.includes('/operator/transfers')) {
        return json([{ transferId: 'tr-1', contractId: CONTRACT_ID, direction: 'IN', counterpartyProviderName: 'Other PS', status: 'REQUESTED', deadline: '2026-11-01', netAmount: null, currency: 'CZK' }])
      }
      return json({}, 404)
    }
    await renderPage(<PensionQueuesPage />)
    expect(calls.some(c => c.url.includes('/api/svc/pension-service/api/v2/pension/operator/onboarding/applications'))).toBe(true)
    expect(screen.getByText('app-1')).toBeTruthy()
    expect(screen.getByText('BALANCED')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'tr-1' }).getAttribute('href')).toBe(`/pension/contracts/${CONTRACT_ID}`)
  })
})
