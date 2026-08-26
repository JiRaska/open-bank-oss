// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import KycPage from '@/app/kyc/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const PARTY = '55555555-5555-5555-5555-555555555555'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('KYC party search', () => {
  it('resolves a customer name through party-service before querying KYC by party id', async () => {
    const f = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/v1/parties/search')) return json({ data: [{ id: PARTY, legalName: 'Oldřich Vaněk', status: 'ACTIVE', kycStatus: 'APPROVED' }] })
      if (url.endsWith(`/api/v1/kyc/cases/party/${PARTY}`)) return json([{ id: 'case-1', partyId: PARTY, status: 'APPROVED', checks: [], updatedAt: '2026-08-20T10:00:00Z', createdAt: '2026-08-20T09:00:00Z' }])
      if (url.endsWith('/api/v1/kyc/cases')) return json([])
      return json({}, 404)
    })
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><KycPage /></LanguageProvider>)

    await waitFor(() => expect(screen.getByLabelText('Search parties')).toBeEnabled())
    fireEvent.change(screen.getByLabelText('Search parties'), { target: { value: 'Oldrich Vanek' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => expect(screen.getByText('Oldřich Vaněk')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'Select Oldřich Vaněk' }))

    await waitFor(() => expect(f.mock.calls.some(([u]) => String(u).endsWith(`/api/v1/kyc/cases/party/${PARTY}`))).toBe(true))
    expect(screen.getByText('All cases')).toBeInTheDocument()
  })
})
