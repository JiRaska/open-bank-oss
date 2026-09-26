// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PaymentsPage from '@/app/payments/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parsePaymentListPage } from '@/lib/payments/paymentListContract'
import { paymentListQuery } from '@/lib/payments/paymentListQuery'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_OPERATOR'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

function id(index: number) {
  return `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
}

function sepa(index: number) {
  return {
    id: id(index), status: 'COMPLETED', amount: 100 + index, currency: 'EUR',
    creditorIban: `DE${String(index).padStart(20, '0')}`, creditorName: `SEPA creditor ${index}`,
    createdAt: `2026-08-${String((index % 27) + 1).padStart(2, '0')}T10:00:00Z`,
  }
}

function domestic(index: number) {
  return {
    id: id(index), status: 'SETTLED', amount: '250.50', currency: 'CZK',
    creditorAccountNumber: String(100000 + index), creditorBankCode: '0800',
    creditorName: `Domestic creditor ${index}`, createdAt: '2026-08-01T10:00:00Z',
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function mount() {
  render(<LanguageProvider><PaymentsPage /></LanguageProvider>)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('payment list evidence', () => {
  it('normalizes the two real list contracts and rejects malformed evidence', () => {
    expect(parsePaymentListPage([sepa(1)], 'SEPA', 50)?.[0]).toMatchObject({ type: 'SEPA', amount: 101 })
    expect(parsePaymentListPage([domestic(2)], 'DOMESTIC', 50)?.[0]).toMatchObject({ type: 'DOMESTIC', amount: 250.5 })
    expect(parsePaymentListPage({ items: [sepa(1)] }, 'SEPA', 50)).toBeNull()
    expect(parsePaymentListPage([{ ...sepa(1), createdAt: 'invalid' }], 'SEPA', 50)).toBeNull()
    expect(parsePaymentListPage([sepa(1), sepa(1)], 'SEPA', 50)).toBeNull()
  })

  it('forwards only the list filters owned by both payment contracts', () => {
    const query = new URLSearchParams({ limit: '50', offset: '100', status: 'PENDING', debtorAccountId: id(7), secret: 'drop-me' })
    expect(paymentListQuery(query)).toBe(`?status=PENDING&debtorAccountId=${id(7)}&limit=50&offset=100`)
    expect(paymentListQuery(new URLSearchParams())).toBe('')
  })

  it('keeps a healthy source usable, names the failed source and continues by offset', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      requests.push(url)
      if (url.startsWith('/api/domestic-payments')) return json({ error: 'upstream' }, 502)
      if (url.includes('offset=50')) return json([sepa(51)])
      return json(Array.from({ length: 50 }, (_, index) => sepa(index + 1)))
    }))

    mount()

    expect(await screen.findByText('SEPA creditor 1')).toBeInTheDocument()
    expect(screen.getByText(/Domestic payment-service is not responding|Domestic payment-service neodpovídá/)).toBeInTheDocument()
    expect(screen.getByText('50', { selector: '.stat-value' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Load more SEPA payments|Načíst další SEPA platby/ }))

    expect(await screen.findByText('SEPA creditor 51')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: /Load more SEPA payments|Načíst další SEPA platby/ })).not.toBeInTheDocument())
    expect(requests.some(url => /[?&]limit=50/.test(url) && /[?&]offset=0/.test(url))).toBe(true)
    expect(requests.some(url => /[?&]offset=50/.test(url))).toBe(true)
  })

  it('never renders two unavailable sources as a genuine empty payment history', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'missing' }, 404)))
    mount()

    await waitFor(() => expect(screen.getAllByText(/is not deployed|není v tomto prostředí nasazená/)).toHaveLength(2))
    expect(screen.queryByText(/No payments found|Nebyly nalezeny žádné platby/)).not.toBeInTheDocument()
  })

  it('purges all previously authorized payment evidence when either source returns 401', async () => {
    let unauthorized = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (unauthorized && url.startsWith('/api/sepa-payments')) return json({ error: 'unauthorized' }, 401)
      return json(url.startsWith('/api/sepa-payments') ? [sepa(1)] : [domestic(2)])
    }))
    mount()

    expect(await screen.findByText('SEPA creditor 1')).toBeInTheDocument()
    expect(screen.getByText('Domestic creditor 2')).toBeInTheDocument()
    unauthorized = true
    fireEvent.click(screen.getByRole('button', { name: /Refresh payments|Obnovit platby/ }))

    await waitFor(() => expect(screen.queryByText('SEPA creditor 1')).not.toBeInTheDocument())
    expect(screen.queryByText('Domestic creditor 2')).not.toBeInTheDocument()
    expect(screen.getAllByText(/session has expired|relace vypršela/)).toHaveLength(2)
  })
})
