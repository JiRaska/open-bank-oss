// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NewPartyPage from '@/app/parties/new/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parseCreatedParty } from '@/lib/party/createdParty'

const push = vi.fn()
const PARTY_ID = '24977cca-20b2-4877-80d1-403b40181a89'

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }))
vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { roles: ['ROLE_OPERATOR'] } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
}))

function response(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 201,
    headers: { 'content-type': 'application/json' },
  })
}

function mount() {
  render(<LanguageProvider><NewPartyPage /></LanguageProvider>)
  fireEvent.change(screen.getByLabelText('Legal Name *'), { target: { value: 'Ada Lovelace' } })
  fireEvent.change(screen.getByLabelText('Email *'), { target: { value: 'ada@example.test' } })
}

afterEach(() => {
  cleanup()
  push.mockReset()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('party creation response trust', () => {
  it('accepts only an object carrying a UUID party identifier', () => {
    expect(parseCreatedParty({ id: PARTY_ID, legalName: 'Ada Lovelace' })).toEqual({ id: PARTY_ID })
    expect(parseCreatedParty({ id: '' })).toBeNull()
    expect(parseCreatedParty({ id: 'party-1' })).toBeNull()
    expect(parseCreatedParty({ data: { id: PARTY_ID } })).toBeNull()
    expect(parseCreatedParty(null)).toBeNull()
  })

  it('does not navigate on an unverified 201 and safely reuses the command key', async () => {
    const keys: string[] = []
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      return keys.length === 1 ? response({ status: 'ACTIVE' }) : response({ id: PARTY_ID })
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')

    mount()
    fireEvent.submit(screen.getByRole('button', { name: 'Create Party' }).closest('form')!)

    expect(await screen.findByRole('alert')).toHaveTextContent('The creation result could not be verified')
    expect(push).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Create Party' }))

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/parties/${PARTY_ID}`))
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ signal: expect.any(AbortSignal) })
    expect(keys).toEqual([
      '11111111-1111-4111-8111-111111111111',
      '11111111-1111-4111-8111-111111111111',
    ])
  })
})
