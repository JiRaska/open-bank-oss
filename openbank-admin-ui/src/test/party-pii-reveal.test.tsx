// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PartyDetailPage from '@/app/parties/[id]/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const session = vi.hoisted(() => ({
  id: 'a67749ee-3abb-44fa-bce2-d5ee2bd255de',
  roles: ['ROLE_KYC'],
}))

vi.mock('next/navigation', () => ({
  useParams: () => ({ id: session.id }),
  useRouter: () => ({ back: vi.fn() }),
}))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: session.roles } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const maskedEmail = 'f*****e@example.test'
const fullEmail = 'fixture@example.test'
const basicParty = {
  id: session.id,
  legalName: 'Synthetic Customer',
  partyType: 'INDIVIDUAL',
  status: 'ACTIVE',
  kycStatus: 'APPROVED',
  createdAt: '2026-10-01T08:00:00Z',
  updatedAt: '2026-10-01T08:00:00Z',
}

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status })
}

function renderParty() {
  const fetcher = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const path = String(input)
    if (path.endsWith('/pii-reveal')) {
      return json({ ...basicParty, email: fullEmail, tradingName: null, phone: null, address: null })
    }
    if (path.endsWith('/api/v1/parties/' + session.id)) {
      return json({
        ...basicParty, email: maskedEmail, piiMasked: true, piiRevealAllowed: session.roles.includes('ROLE_KYC'),
      })
    }
    if (path.includes('/api/v1/accounts')) return json({ data: [] })
    return json({}, 404)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<LanguageProvider><PartyDetailPage /></LanguageProvider>)
  return fetcher
}

const revealButton = /Reveal personal data|Zobrazit osobní údaje/
const hideButton = /Hide personal data|Skrýt osobní údaje/
const reasonLabel = /Reason for viewing personal data|Důvod zobrazení osobních údajů/

beforeEach(() => { session.roles = ['ROLE_KYC'] })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('party detail disclosure', () => {
  it('shows full contact data only after the reasoned request and clears it when hidden', async () => {
    const fetcher = renderParty()
    expect(await screen.findByText(maskedEmail)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: revealButton })).toBeDisabled()
    expect(screen.queryByText(fullEmail)).not.toBeInTheDocument()

    fireEvent.change(screen.getByRole('combobox', { name: reasonLabel }), { target: { value: 'KYC_REVIEW' } })
    fireEvent.click(screen.getByRole('button', { name: revealButton }))
    expect(await screen.findByText(fullEmail)).toBeInTheDocument()
    expect(fetcher.mock.calls.find(([url]) => String(url).endsWith('/pii-reveal'))?.[1]).toMatchObject({
      method: 'POST', body: JSON.stringify({ reason: 'KYC_REVIEW' }), cache: 'no-store',
    })

    fireEvent.click(screen.getByRole('button', { name: hideButton }))
    expect(screen.getByText(maskedEmail)).toBeInTheDocument()
    expect(screen.queryByText(fullEmail)).not.toBeInTheDocument()
  })

  it('gives a read-only user a masked profile without a reveal control', async () => {
    session.roles = ['ROLE_VIEWER']
    const fetcher = renderParty()
    expect(await screen.findByText(maskedEmail)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: revealButton })).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(([url]) => String(url).endsWith('/pii-reveal'))).toBe(false)
  })
})
