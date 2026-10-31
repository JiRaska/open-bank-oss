// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SddPage from '@/app/sdd/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_OPERATOR'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('SDD response recovery', () => {
  it('reports malformed successful payloads as data errors and lets refresh recover', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({ mandates: [] }))
      .mockResolvedValueOnce(response([]))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><SddPage /></LanguageProvider>)

    expect(await screen.findByText('Failed to load: Direct debit mandates')).toBeVisible()
    expect(screen.queryByText('sdd-service is not responding')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh direct debit mandates' }))
    await waitFor(() => expect(screen.getByText('No mandates')).toBeVisible())
    expect(screen.queryByText('Failed to load: Direct debit mandates')).not.toBeInTheDocument()
  })
  it('renders every mandate when creditor identifiers use the authoritative opaque-string contract', async () => {
    const base = {
      id: '11111111-1111-4111-8111-111111111111', accountId: '22222222-2222-4222-a222-222222222222',
      debtorIban: 'CZ6508000000192000145399', creditorIdentifier: 'CZ98ZZZ00000000001', umr: 'SYNTHETIC-NORMAL',
      scheme: 'B2B', sequenceType: 'RCUR', creditorName: 'Synthetic Utilities', debtorName: 'Synthetic Company',
      signatureDate: '2026-01-15', status: 'ACTIVE', b2bConfirmed: true,
      lastCollectionDate: null, lastPreNotificationDate: null, createdAt: '2026-01-15T08:00:00Z', amendments: [],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([base,
      { ...base, id: '33333333-3333-3333-b333-333333333333', umr: 'SYNTHETIC-OPAQUE-1', creditorIdentifier: 'opaque-legacy-id' },
      { ...base, id: '44444444-4444-4444-a444-444444444444', umr: 'SYNTHETIC-OPAQUE-2', creditorIdentifier: 'legacy/id:2' },
    ])))
    render(<LanguageProvider initialLanguage="en"><SddPage /></LanguageProvider>)
    expect(await screen.findByText('SYNTHETIC-NORMAL')).toBeVisible()
    expect(screen.getByText('SYNTHETIC-OPAQUE-1')).toBeVisible()
    expect(screen.getByText('SYNTHETIC-OPAQUE-2')).toBeVisible()
    expect(screen.getByText('opaque-legacy-id')).toBeVisible()
    expect(screen.getByText('legacy/id:2')).toBeVisible()
    expect(screen.queryByText('Failed to load: Direct debit mandates')).not.toBeInTheDocument()
  })

})
