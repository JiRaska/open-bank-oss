// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ContextAssignmentAdministration } from '@/components/context/ContextAssignmentAdministration'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('context assignment administration', () => {
  it('requires an explicit second step before revoking access', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (url.endsWith('/pending')) return Response.json([])
      return Response.json([{
        id: 'assignment-1', principalId: 'analyst-1', caseId: 'case-7',
        purpose: 'PAYMENT_COMPLAINT', validTo: '2026-09-15T10:00:00Z',
      }])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><ContextAssignmentAdministration /></LanguageProvider>)

    await screen.findByText(/analyst-1 · PAYMENT_COMPLAINT/)
    fireEvent.click(screen.getByRole('button', { name: 'Revoke' }))

    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
    expect(screen.getByRole('group', { name: 'Confirm access revocation' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Confirm revoke' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(true))
    expect(await screen.findByText('Access was revoked immediately.')).toBeInTheDocument()
  })

  it('shows the decision basis and requires confirmation before approval', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'PATCH') return new Response(null, { status: 204 })
      if (url.endsWith('/pending')) return Response.json([{
        id: 'proposal-1', principalId: 'analyst-1', caseId: 'case-7', purpose: 'PAYMENT_COMPLAINT',
        validTo: '2026-09-15T10:00:00Z', makerId: 'maker-1',
      }])
      return Response.json([])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><ContextAssignmentAdministration /></LanguageProvider>)

    expect(await screen.findByText(/Proposed by: maker-1/)).toHaveTextContent('Valid until')
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))

    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(false)
    expect(screen.getByRole('group', { name: 'Confirm access approval' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Confirm approve' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(true))
    expect(await screen.findByText('The decision was recorded in the audit trail.')).toBeInTheDocument()
  })
})
