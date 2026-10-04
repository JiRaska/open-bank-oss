// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { act, cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { isProposer } from '@/lib/lending/fourEyes'

const authState: { user: { id: string; name?: string; email?: string } | undefined } = { user: undefined }
vi.mock('@/lib/auth/useAuth', () => ({ useAuth: () => ({ user: authState.user, roles: [] }) }))
vi.mock('@/components/entities/EntityChip', () => ({ EntityChip: ({ id }: { id: string }) => <span>{id}</span> }))

import ApplicationFlowPage from '@/app/lending/applications/[id]/page'

const APP = {
  id: 'app-1', partyId: 'party-1', status: 'FOUR_EYES', proposedBy: 'alice@openbank.local',
  requestedAmount: { amount: '10000.00', currency: 'EUR' },
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

async function mount(app: Record<string, unknown>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) =>
    String(url).includes('/evidence') ? json({ events: [] }) : json(app)))
  await act(async () => {
    render(<LanguageProvider><ApplicationFlowPage params={Promise.resolve({ id: 'app-1' })} /></LanguageProvider>)
  })
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('openbank-admin-lang', 'cs')
  authState.user = undefined
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('lending application in the four-eyes state', () => {
  it('says it awaits a second person and names the proposer, with no advance action anywhere', async () => {
    authState.user = { id: 'bob-sub', email: 'bob@openbank.local' }
    await mount(APP)
    expect(await screen.findByTestId('four-eyes-waiting')).toHaveTextContent('Čeká na rozhodnutí druhé osoby (čtyři oči)')
    expect(screen.getByTestId('four-eyes-proposer')).toHaveTextContent('alice@openbank.local')
    expect(screen.queryByRole('button', { name: /posun|advance/i })).toBeNull()
    expect(screen.queryByTestId('four-eyes-own')).toBeNull()
    expect(screen.getByTestId('decide-disabled')).toBeDisabled()
  })

  it('tells the proposer why they cannot decide their own application', async () => {
    authState.user = { id: 'alice-sub', email: 'Alice@openbank.local' }
    await mount(APP)
    expect(await screen.findByTestId('four-eyes-own')).toHaveTextContent('nemůžete rozhodnout')
    expect(screen.getByTestId('decide-disabled')).toHaveAttribute('aria-describedby', 'four-eyes-own')
  })

  it('shows no four-eyes notice outside the four-eyes state', async () => {
    authState.user = { id: 'alice-sub', email: 'alice@openbank.local' }
    await mount({ ...APP, status: 'OFFERED' })
    await screen.findByTestId('decide-disabled')
    expect(screen.queryByTestId('four-eyes-waiting')).toBeNull()
    expect(screen.queryByTestId('four-eyes-own')).toBeNull()
  })

  it('matches the proposer on any identity the service may have recorded, and never on unknowns', () => {
    expect(isProposer('alice', { id: 'x', name: 'alice' })).toBe(true)
    expect(isProposer('sub-1', { id: 'sub-1' })).toBe(true)
    expect(isProposer('alice', { id: 'bob' })).toBe(false)
    expect(isProposer(undefined, { id: 'bob' })).toBe(false)
    expect(isProposer('alice', undefined)).toBe(false)
  })
})
