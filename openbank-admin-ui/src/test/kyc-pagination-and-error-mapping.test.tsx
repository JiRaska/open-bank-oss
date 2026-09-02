// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import KycPage from '@/app/kyc/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const PARTY = '66666666-6666-6666-6666-666666666666'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

const kycCase = (id: string) => ({ id, partyId: PARTY, status: 'OPEN', checks: [], updatedAt: '2026-09-01T00:00:00Z', createdAt: '2026-09-01T00:00:00Z' })

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

/**
 * Issue #8163: two truthfulness defects in this page — (1) it requested no page/size and
 * silently showed the default first 20 records as the whole KYC queue, and (2) it converted an
 * HTTP 405 from either list route into `no_data`, though only a genuine party-lookup 404 means
 * "no case exists". This file drives the real page against the corrected
 * `{items,total,page,size,statusFilter}` envelope (openapi.yaml 1.8.0, #8164).
 */
describe('KYC case list — server-backed pagination', () => {
  it('requests page 0 explicitly and advances to page 1 on Next, without losing the total', async () => {
    const calls: string[] = []
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      calls.push(url)
      if (url.includes('page=1')) {
        return json({ items: [kycCase('case-page1')], total: 25, page: 1, size: 20, statusFilter: null })
      }
      if (url.includes('/api/v1/kyc/cases?')) {
        return json({ items: Array.from({ length: 20 }, (_, i) => kycCase(`case-${i}`)), total: 25, page: 0, size: 20, statusFilter: null })
      }
      return json({}, 404)
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText(/25/)).toBeInTheDocument())
    expect(calls.some(u => u.includes('page=0') && u.includes('size=20'))).toBe(true)

    const next = screen.getByRole('button', { name: 'Next page' })
    expect(next).toBeEnabled()
    fireEvent.click(next)

    await waitFor(() => expect(calls.some(u => u.includes('page=1'))).toBe(true))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Previous page' })).toBeEnabled())
  })

  it('disables Next on the last page instead of implying more records exist', async () => {
    const f = vi.fn(async () => json({ items: [kycCase('only-case')], total: 1, page: 0, size: 20, statusFilter: null }))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('only-case'.slice(0, 8), { exact: false })).toBeTruthy())
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
  })

  it('drops a stale party-scoped response that resolves after "All cases" was clicked (out-of-order network)', async () => {
    // "All cases" is the one control in this page that stays enabled while a request is in
    // flight (it must, so an operator can always escape a stuck filter) — which makes it the
    // reachable path for issue #8163's stale/out-of-order requirement: a slow response for the
    // OLD scope must not clobber the state of the NEW scope once it lands late.
    let resolvePartyLookup: ((r: Response) => void) | null = null
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/v1/parties/search')) {
        return json({ data: [{ id: PARTY, legalName: 'Stale Party', status: 'ACTIVE', kycStatus: 'PENDING' }] })
      }
      if (url.endsWith(`/api/v1/kyc/cases/party/${PARTY}`)) {
        // Deliberately held open past the "All cases" click below.
        return new Promise<Response>(resolve => { resolvePartyLookup = resolve })
      }
      if (url.includes('/api/v1/kyc/cases?')) {
        return json({ items: [kycCase('fresh-list-case')], total: 1, page: 0, size: 20, statusFilter: null })
      }
      return json({}, 404)
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByLabelText('Search parties')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Search parties'), { target: { value: 'Stale Party' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(screen.getByText('Stale Party')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Select Stale Party' }))

    // The party-scoped request is now in flight and held. Escape it via "All cases" (not
    // disabled while loading) before it resolves.
    await waitFor(() => expect(screen.getByText('All cases')).toBeInTheDocument())
    fireEvent.click(screen.getByText('All cases'))

    await waitFor(() => expect(screen.getByText('fresh-li', { exact: false })).toBeInTheDocument())

    // Now let the superseded party-scoped response resolve — it must NOT overwrite the list.
    resolvePartyLookup?.(json([kycCase('stale-party-case')]))
    await new Promise(r => setTimeout(r, 0))

    expect(screen.getByText('fresh-li', { exact: false })).toBeInTheDocument()
    expect(screen.queryByText('stale-pa', { exact: false })).not.toBeInTheDocument()
  })
})

describe('KYC case list — 404 vs 405 truthfulness', () => {
  it('maps a genuine party-lookup 404 to the no-data empty state', async () => {
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/v1/parties/search')) return json({ data: [{ id: PARTY, legalName: 'Petr Novák', status: 'ACTIVE', kycStatus: 'PENDING' }] })
      if (url.endsWith(`/api/v1/kyc/cases/party/${PARTY}`)) return json({}, 404)
      return json({}, 404)
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByLabelText('Search parties')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Search parties'), { target: { value: 'Petr Novak' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(screen.getByText('Petr Novák')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Select Petr Novák' }))

    await waitFor(() => expect(screen.getByText('No KYC case was found for this party.')).toBeInTheDocument())
  })

  it('does NOT map a 405 route-contract failure to no-data — it must read as unavailable', async () => {
    const f = vi.fn(async () => json({ error: 'method not allowed' }, 405))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.queryByText(/^No KYC cases found\.$/)).not.toBeInTheDocument())
  })

  it('does NOT map an unscoped 404 on the list route to no-data (only party-lookup 404 qualifies)', async () => {
    const f = vi.fn(async () => json({}, 404))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.queryByText(/^No KYC cases found\.$/)).not.toBeInTheDocument())
  })
})
