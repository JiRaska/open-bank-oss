// SPDX-License-Identifier: Apache-2.0

import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PartyDetailPage from '@/app/parties/[id]/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const PARTY_ID = '11111111-1111-4111-8111-111111111111'
const CASE_ID = '22222222-2222-4222-8222-222222222222'

vi.mock('next/navigation', () => ({
  useParams: () => ({ id: PARTY_ID }),
  useRouter: () => ({ back: vi.fn() }),
}))

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_OPERATOR'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const party = {
  id: PARTY_ID, partyType: 'INDIVIDUAL', status: 'ACTIVE', legalName: 'Named Customer',
  email: 'customer@example.test', kycStatus: 'APPROVED',
  createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}
const kyc = {
  id: CASE_ID, partyId: PARTY_ID, status: 'APPROVED', riskLevel: 'LOW', checks: [],
  createdAt: '2026-09-10T05:00:00Z', updatedAt: '2026-09-10T06:00:00Z',
}
const json = (body: unknown) => new Response(JSON.stringify(body), {
  status: 200, headers: { 'content-type': 'application/json' },
})

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('party detail KYC case reference', () => {
  it('uses the verified party name as the primary case label without another lookup', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith(`/api/v1/parties/${PARTY_ID}`)) return json(party)
      if (url.endsWith(`/api/v1/kyc/cases/party/${PARTY_ID}`)) return json(kyc)
      if (url.includes('/api/v1/accounts?')) return json({ data: [] })
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><PartyDetailPage /></LanguageProvider>)

    expect(await screen.findByText('KYC case for Named Customer')).toBeVisible()
    expect(screen.queryByText(CASE_ID)).not.toBeInTheDocument()
    expect(screen.getByTitle(CASE_ID)).toHaveTextContent('22222222…')
    expect(screen.getByRole('button', { name: 'Copy KYC case ID' })).toBeVisible()
    // The page also loads its existing related-accounts card; the reference adds no fourth fetch.
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
  })

  it('does not render a case for a different party', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      String(input).includes('/kyc/cases/party/')
        ? json({ ...kyc, partyId: '33333333-3333-4333-8333-333333333333' })
        : json(party)))
    render(<LanguageProvider initialLanguage="en"><PartyDetailPage /></LanguageProvider>)

    expect(await screen.findByText('Failed to load: KYC case')).toBeInTheDocument()
    expect(screen.queryByText('KYC case for Named Customer')).not.toBeInTheDocument()
  })
})
