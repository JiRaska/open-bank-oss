// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CustomerPortfolioPanel } from '@/components/party/CustomerPortfolioPanel'

const PARTY = '55555555-5555-5555-5555-555555555555'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('Customer 360 authoritative portfolio', () => {
  it('uses the shared party-filtered graph contract for every owning domain', async () => {
    const f = vi.fn(async () => json({
      accounts: [{ id: 'a1', status: 'ACTIVE' }, { id: 'a2', status: 'BLOCKED' }],
      lendingApplications: [{ id: 'l1', status: 'ASSESSMENT' }],
      amlCases: [{ id: 'c1', status: 'OPEN' }], unavailable: [],
    }))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><CustomerPortfolioPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('ACTIVE · BLOCKED')).toBeInTheDocument())
    const urls = f.mock.calls.map(([u]) => String(u))
    expect(urls).toEqual([`/api/customer-360/${PARTY}/graph`])
    expect(screen.getByText('ASSESSMENT')).toBeInTheDocument()
    expect(screen.getByText('OPEN')).toBeInTheDocument()
  })

  it('degrades one source without hiding successful sources', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      accounts: [], lendingApplications: [], amlCases: [], unavailable: ['lending'],
    })))
    render(<LanguageProvider><CustomerPortfolioPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText(/Unavailable · unreachable/i)).toBeInTheDocument())
    expect(screen.getAllByText('0')).toHaveLength(2)
  })

  it('marks capped owning-service windows as lower bounds', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      accounts: Array.from({ length: 50 }, (_, index) => ({ id: `a${index}`, status: 'ACTIVE' })),
      lendingApplications: [{ id: 'l1', status: 'ASSESSMENT' }],
      amlCases: Array.from({ length: 30 }, (_, index) => ({ id: `c${index}`, status: 'OPEN' })),
      unavailable: [],
    })))
    render(<LanguageProvider><CustomerPortfolioPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('50+')).toBeInTheDocument())
    expect(screen.getByText('30+')).toBeInTheDocument()
    expect(screen.getByLabelText('At least 50')).toBeInTheDocument()
    expect(screen.getByLabelText('At least 30')).toBeInTheDocument()
    expect(screen.getAllByText('At least this many; the service returned a full paginated window.')).toHaveLength(2)
    expect(screen.getByText('1')).toBeInTheDocument()
  })
})
