// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import KycPage from '@/app/kyc/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const PARTY = '55555555-5555-5555-5555-555555555555'
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const kycCase = (n: number) => ({
  id: `0000000${n}-0000-0000-0000-00000000000${n}`,
  partyId: PARTY,
  status: 'OPEN',
  riskLevel: 'LOW',
  checks: [],
  createdAt: '2026-09-01T09:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
})

/** The envelope kyc-service publishes as `KycCasePage` (openapi.yaml 1.8.0). */
const casePage = (page: number, total: number, items: ReturnType<typeof kycCase>[]) =>
  ({ items, total, page, size: 20, statusFilter: null })

const listCalls = (f: ReturnType<typeof vi.fn>) =>
  f.mock.calls.map(([u]) => String(u)).filter(u => u.includes('/api/v1/kyc/cases?'))

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

/**
 * The consumer half of the #8163 contract alignment. The provider half —
 * `KycCasePageApiContractTest` in openbank-kyc-service — replays the committed `openapi.yaml`
 * against the running service; this asserts the page actually SPEAKS that contract: it asks for a
 * window, it reads the envelope, and it stops reporting a broken route as an empty result set.
 */
describe('KYC case list contract', () => {
  it('requests an explicit page window and pages through it using the envelope total', async () => {
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/v1/kyc/cases?page=0&size=20')) return json(casePage(0, 25, [kycCase(1)]))
      if (url.includes('/api/v1/kyc/cases?page=1&size=20')) return json(casePage(1, 25, [kycCase(2)]))
      return json({}, 404)
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    // The window is in the request. Before #8163 the page asked for no page/size at all and took
    // whatever default the service happened to apply.
    await waitFor(() => expect(listCalls(f)).toContain('/api/svc/kyc-service/api/v1/kyc/cases?page=0&size=20'))
    await waitFor(() => expect(screen.getByText('Showing 1–1 of 25 cases')).toBeInTheDocument())

    const next = screen.getByRole('button', { name: 'Next KYC cases page' })
    await waitFor(() => expect(next).toBeEnabled())
    fireEvent.click(next)

    await waitFor(() => expect(listCalls(f)).toContain('/api/svc/kyc-service/api/v1/kyc/cases?page=1&size=20'))
    await waitFor(() => expect(screen.getByText('Showing 21–21 of 25 cases')).toBeInTheDocument())
  })

  it('offers no next page once the envelope total is exhausted', async () => {
    const f = vi.fn(async () => json(casePage(0, 1, [kycCase(1)])))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('Showing 1–1 of 1 case')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Next KYC cases page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous KYC cases page' })).toBeDisabled()
  })

  it('surfaces a 405 on the case list as a failure, not as "no KYC cases found"', async () => {
    // The regression this replaces. `GET /api/v1/kyc/cases` is declared to answer 200 with a
    // KycCasePage on every call, empty page included — so a 405 is a route served for some other
    // method, i.e. an outage. The page used to paint it as the calm empty state, reporting a
    // broken deployment to the operator as a fact about the data.
    const f = vi.fn(async () => json({ error: 'method_not_allowed' }, 405))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    // The two states render different <DataUnavailable> copy, and only that distinguishes them —
    // asserting the absence of the table's own "No KYC cases found." row would pass in both
    // worlds, because the row is suppressed whenever `unavailable` is set at all.
    await waitFor(() => expect(screen.getByText('Failed to load: KYC cases')).toBeInTheDocument())
    expect(screen.queryByText('No data yet: KYC cases')).not.toBeInTheDocument()
  })

  it('still degrades to the calm empty state when a party genuinely has no case', async () => {
    // 404 on the PARTY-scoped route is that route's documented answer to a lookup that misses —
    // the one place where "no data" is a true statement rather than a hidden failure.
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes(`/api/v1/kyc/cases/party/${PARTY}`)) return json({ error: 'not found' }, 404)
      return json(casePage(0, 0, []))
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByLabelText('Filter by Party ID')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Filter by Party ID'), { target: { value: PARTY } })
    fireEvent.click(screen.getByRole('button', { name: 'Search KYC cases' }))

    await waitFor(() => expect(screen.getAllByText('No KYC case was found for this party.').length).toBeGreaterThan(0))
  })

  it('refuses a body that is not the published envelope instead of rendering an empty table', async () => {
    // A bare array is the pre-#8163 tolerated shape. Accepting anything JSON-shaped is what let a
    // drift read as "no cases" — the same silence the provider replay now closes from its side.
    const f = vi.fn(async () => json([kycCase(1)]))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('Failed to load: KYC cases')).toBeInTheDocument())
    // The row the tolerated shape would have produced. Its absence is the assertion: a body that
    // is not the contract must not reach the table wearing the contract's clothes.
    expect(screen.queryByText(`${kycCase(1).id.slice(0, 8)}…`)).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'KYC cases pagination' })).not.toBeInTheDocument()
  })
})
