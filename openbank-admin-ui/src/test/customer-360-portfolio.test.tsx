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
  it('queries each owning service by literal party-filtered route', async () => {
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('account-service')) return json([{ status: 'ACTIVE' }, { status: 'BLOCKED' }])
      if (url.includes('lending-service')) return json({ items: [{ state: 'ASSESSMENT' }] })
      return json({ data: [{ status: 'OPEN' }] })
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><CustomerPortfolioPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('ACTIVE · BLOCKED')).toBeInTheDocument())
    const urls = f.mock.calls.map(([u]) => String(u))
    expect(urls).toContain(`/api/svc/account-service/api/v1/accounts?partyId=${PARTY}&limit=100`)
    expect(urls).toContain(`/api/svc/lending-service/api/v1/lending/applications?partyId=${PARTY}`)
    expect(urls).toContain(`/api/svc/aml-service/api/v1/aml/cases?partyId=${PARTY}&limit=100&offset=0`)
    expect(screen.getByText('ASSESSMENT')).toBeInTheDocument()
    expect(screen.getByText('OPEN')).toBeInTheDocument()
  })

  it('degrades one source without hiding successful sources', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('lending-service')) return json({ error: 'unreachable' }, 502)
      return json([])
    }))
    render(<LanguageProvider><CustomerPortfolioPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText(/Unavailable · error/i)).toBeInTheDocument())
    expect(screen.getAllByText('0')).toHaveLength(2)
  })
})
