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
  it('submits the exact approved authority root', async () => {
    const rootRef = 'delegation:44444444-4444-4444-4444-444444444444'
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') return Response.json({ id: 'proposal-1' }, { status: 201 })
      return Response.json([])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><ContextAssignmentAdministration /></LanguageProvider>)
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'AUTHORIZATION_REVIEW' } })
    fireEvent.change(screen.getByLabelText('Principal'), { target: { value: 'analyst-1' } })
    fireEvent.change(screen.getByLabelText('Case ID'), { target: { value: 'case-1' } })
    fireEvent.change(screen.getByLabelText('Approved root'), { target: { value: rootRef } })
    fireEvent.submit(screen.getByRole('button', { name: 'Propose' }).closest('form')!)
    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
    const init = fetchMock.mock.calls.find(([, value]) => value?.method === 'POST')![1]!
    expect(JSON.parse(String(init.body))).toMatchObject({ purpose: 'AUTHORIZATION_REVIEW', caseId: 'case-1', rootRef })
  })

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
