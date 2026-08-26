// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The issue flow, driven the way an operator drives it: type a name, pick the
// person, pick their account — and assert that the POST card-issuance receives was
// assembled entirely out of those clicks. The claim being tested is the design
// constraint itself: no UUID is ever typed, and the productCode/currency are
// derived, not guessed.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { IssueCardDialog } from '@/components/cards/IssueCardDialog'

const PARTY = { id: 'party-uuid-1', legalName: 'Bohuslava Čermáková', email: 'b@example.test', status: 'ACTIVE' }
const ACCOUNT = {
  id: 'account-uuid-1', accountNumber: 'CZ6508000000192000145399', accountType: 'CURRENT',
  partyId: 'party-uuid-1', productId: 'product-uuid-1', currencyCode: 'CZK', status: 'ACTIVE',
}
const ENTITLEMENTS = {
  productCode: 'CURRENT_CZK', maxCards: 3, issued: 1, remaining: 2,
  virtualCardAllowed: true, singleUseAllowed: true, networks: ['VISA', 'MASTERCARD'],
  tiers: [], monthlyFeePerCard: 0, enabled: true, source: 'CATALOG',
}

const posted: { url: string; init: RequestInit }[] = []

function stubFleet() {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
    if (init?.method === 'POST') {
      posted.push({ url, init })
      return new Response(JSON.stringify({ id: 'card-uuid-1', maskedPan: '**** 4242' }), { status: 201, headers: { 'content-type': 'application/json' } })
    }
    if (url.includes('/parties/search')) return ok({ data: [PARTY], pagination: {} })
    if (url.includes('/accounts?') || url.includes('/accounts&')) return ok({ data: [ACCOUNT], pagination: {} })
    if (url.includes('/products/product-uuid-1')) return ok({ id: 'product-uuid-1', code: 'CURRENT_CZK' })
    if (url.includes('/entitlements')) return ok(ENTITLEMENTS)
    throw new TypeError(`unstubbed ${url}`)
  })
}

const mount = (onIssued = vi.fn()) => {
  render(React.createElement(LanguageProvider, null,
    React.createElement(IssueCardDialog, { onClose: vi.fn(), onIssued })))
  return onIssued
}

beforeEach(() => { posted.length = 0; vi.stubGlobal('fetch', stubFleet()); vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID: () => 'idem-key-1' }) })
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function walkToReview() {
  fireEvent.change(screen.getByLabelText('Search for a client'), { target: { value: 'Čerm' } })
  await waitFor(() => expect(screen.getByText('Bohuslava Čermáková')).toBeTruthy(), { timeout: 2000 })
  fireEvent.click(screen.getByText('Bohuslava Čermáková'))
  await waitFor(() => expect(screen.getByText(ACCOUNT.accountNumber)).toBeTruthy(), { timeout: 2000 })
  fireEvent.click(screen.getByText(ACCOUNT.accountNumber))
  // The product code is resolved from the account, never typed.
  await waitFor(() => expect(screen.getByText('CURRENT_CZK')).toBeTruthy(), { timeout: 2000 })
}

describe('issue-card flow', () => {
  it('keeps keyboard focus inside the modal', () => {
    mount()
    const dialog = screen.getByRole('dialog', { name: 'Issue a card' })
    const close = screen.getByRole('button', { name: 'Close' })
    const search = screen.getByLabelText('Search for a client')

    search.focus()
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(close)

    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(search)
  })

  it('walks party → account → product and shows the entitlement context', async () => {
    mount()
    await walkToReview()
    expect(screen.getByText('Cards: 1 of 3')).toBeTruthy()
  })

  it('assembles the POST out of the selections, with a stable Idempotency-Key', async () => {
    mount()
    await walkToReview()
    fireEvent.click(screen.getByText('Continue'))
    await waitFor(() => expect(screen.getByText('Issue the card')).toBeTruthy())
    fireEvent.click(screen.getByText('Issue the card'))

    await waitFor(() => expect(posted).toHaveLength(1))
    const { url, init } = posted[0]
    expect(url).toContain('/api/svc/card-issuance-service/api/v1/cards')
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBe('idem-key-1')
    expect(JSON.parse(String(init.body))).toEqual({
      partyId: 'party-uuid-1',
      accountId: 'account-uuid-1',
      productCode: 'CURRENT_CZK',
      cardType: 'DEBIT',
      network: 'VISA',
      cardholderName: 'Bohuslava Čermáková',
      embossedName: 'BOHUSLAVA CERMAKOVA',
      currency: 'CZK',
      dailyLimitMinorUnits: 500_000,
      monthlyLimitMinorUnits: 5_000_000,
    })
    // The BFF stamps the operator identity server-side; a client-set one would be
    // an audit field the audited party controls.
    expect(JSON.stringify(init.headers)).not.toContain('Operator')
  })

  it('rejects a double click before React can render the issue button disabled', async () => {
    const randomUUID = vi.fn().mockReturnValueOnce('idem-key-first').mockReturnValueOnce('idem-key-second')
    vi.stubGlobal('crypto', { ...globalThis.crypto, randomUUID })
    mount()
    await walkToReview()
    fireEvent.click(screen.getByText('Continue'))
    await waitFor(() => expect(screen.getByText('Issue the card')).toBeTruthy())

    const issueButton = screen.getByText('Issue the card').closest('button') as HTMLButtonElement
    fireEvent.click(issueButton)
    fireEvent.click(issueButton)

    await waitFor(() => expect(posted).toHaveLength(1))
    expect((posted[0].init.headers as Record<string, string>)['Idempotency-Key']).toBe('idem-key-first')
    expect(randomUUID).toHaveBeenCalledTimes(1)
  })

  it('refuses an exhausted quota up front and says why', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })
      if (url.includes('/parties/search')) return ok({ data: [PARTY] })
      if (url.includes('/accounts?')) return ok({ data: [ACCOUNT] })
      if (url.includes('/products/')) return ok({ code: 'CURRENT_CZK' })
      if (url.includes('/entitlements')) return ok({ ...ENTITLEMENTS, issued: 3, remaining: 0 })
      throw new TypeError(`unstubbed ${url}`)
    }))
    mount()
    await walkToReview()
    await waitFor(() => expect(screen.getByText('The client already holds every card this product allows.')).toBeTruthy())
    expect((screen.getByText('Continue').closest('button') as HTMLButtonElement).disabled).toBe(true)
  })
})
