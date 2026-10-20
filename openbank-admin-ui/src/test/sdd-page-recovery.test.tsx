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
})
