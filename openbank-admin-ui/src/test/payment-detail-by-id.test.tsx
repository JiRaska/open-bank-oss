// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PaymentDetailPage from '@/app/payments/[id]/page'

const PAYMENT_ID = 'payment/id 42'
const BY_ID_URL = '/api/svc/sepa-payment/api/v1/sepa-payments/payment%2Fid%2042'
const DOMESTIC_BY_ID_URL = '/api/svc/domestic-payment/api/v1/domestic-payments/payment%2Fid%2042'
let paymentId = PAYMENT_ID
let paymentType = 'SEPA'

vi.mock('next/navigation', () => ({
  useParams: () => ({ id: paymentId }),
  useSearchParams: () => new URLSearchParams(`type=${paymentType}`),
}))

vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

beforeEach(() => {
  paymentId = PAYMENT_ID
  paymentType = 'SEPA'
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('payment detail by-id loading', () => {
  it('loads the selected SEPA payment from its encoded by-id BFF URL without listing the collection', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === BY_ID_URL) {
        return json({
          id: PAYMENT_ID,
          status: 'COMPLETED',
          amount: 125,
          currency: 'EUR',
          creditorName: 'By-ID creditor',
        })
      }
      if (url === '/api/sepa-payments') throw new Error('collection URL must not be used')
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => { render(<PaymentDetailPage />) })

    expect(await screen.findByText('By-ID creditor')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(fetchMock.mock.calls[0]?.[0]).toBe(BY_ID_URL)
  })

  it('keeps a handed-off preview visible but marks it stale when the by-id refresh is unavailable', async () => {
    window.sessionStorage.setItem(`ob:row:payments:${PAYMENT_ID}`, JSON.stringify({
      id: PAYMENT_ID,
      type: 'SEPA',
      status: 'RECEIVED',
      amount: 99,
      currency: 'EUR',
      creditorName: 'Saved preview creditor',
    }))
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      expect(String(input)).toBe(BY_ID_URL)
      return json({ error: 'scaled_to_zero' }, 503)
    }))

    await act(async () => { render(<PaymentDetailPage />) })

    expect(await screen.findByText('Saved preview creditor')).toBeInTheDocument()
    expect(await screen.findByText('Live payment unavailable — showing saved preview')).toBeInTheDocument()
    expect(screen.getByText(/may be stale/i)).toBeInTheDocument()
  })

  it('keeps re-authentication guidance visible when an expired session leaves only a saved preview', async () => {
    window.sessionStorage.setItem(`ob:row:payments:${PAYMENT_ID}`, JSON.stringify({
      id: PAYMENT_ID,
      type: 'SEPA',
      status: 'RECEIVED',
      creditorName: 'Preview requiring sign-in',
    }))
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'unauthorized' }, 401)))

    await act(async () => { render(<PaymentDetailPage />) })

    expect(await screen.findByText('Preview requiring sign-in')).toBeInTheDocument()
    expect(await screen.findByText('Session expired — showing saved preview')).toBeInTheDocument()
    expect(screen.getByText(/may be stale\. Sign in again/i)).toBeInTheDocument()
  })

  it('keeps domestic rail selection on the domestic by-id service and path', async () => {
    paymentType = 'DOMESTIC'
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      expect(String(input)).toBe(DOMESTIC_BY_ID_URL)
      return json({ id: PAYMENT_ID, status: 'SETTLED', creditorName: 'Domestic creditor' })
    })
    vi.stubGlobal('fetch', fetchMock)

    await act(async () => { render(<PaymentDetailPage />) })

    expect(await screen.findByText('Domestic creditor')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('ignores a superseded rail response that resolves after the current request', async () => {
    let resolveSepa: ((response: Response) => void) | undefined
    let resolveDomestic: ((response: Response) => void) | undefined
    const sepaResponse = new Promise<Response>(resolve => { resolveSepa = resolve })
    const domesticResponse = new Promise<Response>(resolve => { resolveDomestic = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === BY_ID_URL) return sepaResponse
      if (url === DOMESTIC_BY_ID_URL) return domesticResponse
      return Promise.reject(new Error(`unexpected request: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    let view!: ReturnType<typeof render>
    await act(async () => { view = render(<PaymentDetailPage />) })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    paymentType = 'DOMESTIC'
    await act(async () => { view.rerender(<PaymentDetailPage />) })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))

    await act(async () => {
      resolveDomestic?.(json({ id: PAYMENT_ID, status: 'SETTLED', creditorName: 'Current domestic payment' }))
      await domesticResponse
    })
    expect(await screen.findByText('Current domestic payment')).toBeInTheDocument()

    await act(async () => {
      resolveSepa?.(json({ id: PAYMENT_ID, status: 'COMPLETED', creditorName: 'Superseded SEPA payment' }))
      await sepaResponse
    })
    expect(screen.queryByText('Superseded SEPA payment')).not.toBeInTheDocument()
    expect(screen.getByText('Current domestic payment')).toBeInTheDocument()
  })

  it('never carries the previous payment or a mismatched stash across a failed route change', async () => {
    const nextId = 'domestic/id 77'
    const nextUrl = '/api/svc/domestic-payment/api/v1/domestic-payments/domestic%2Fid%2077'
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === BY_ID_URL) return json({ id: PAYMENT_ID, status: 'COMPLETED', creditorName: 'Previous SEPA payment' })
      if (url === nextUrl) return json({ error: 'scaled_to_zero' }, 503)
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    let view!: ReturnType<typeof render>
    await act(async () => { view = render(<PaymentDetailPage />) })
    expect(await screen.findByText('Previous SEPA payment')).toBeInTheDocument()

    window.sessionStorage.setItem(`ob:row:payments:${nextId}`, JSON.stringify({
      id: nextId,
      type: 'SEPA',
      creditorName: 'Wrong-rail saved row',
    }))
    paymentId = nextId
    paymentType = 'DOMESTIC'
    await act(async () => { view.rerender(<PaymentDetailPage />) })

    expect(await screen.findByText('Domestic-payment is idle (scaled to zero)')).toBeInTheDocument()
    expect(screen.queryByText('Previous SEPA payment')).not.toBeInTheDocument()
    expect(screen.queryByText('Wrong-rail saved row')).not.toBeInTheDocument()
    expect(screen.queryByText('Live payment unavailable — showing saved preview')).not.toBeInTheDocument()
  })
})
