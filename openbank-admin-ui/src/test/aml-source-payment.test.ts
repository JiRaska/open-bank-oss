// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { LEGACY_ORDER, SOURCES, fetchSourcePayment, k8sServiceOf, projectSourcePayment } from '@/lib/aml/sourcePayment'

const id = '0bc7049c-5095-4112-9021-6f25666008d5'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
afterEach(() => vi.unstubAllGlobals())

describe('source payment projection', () => {
  it('reads each rail in its own shape', () => {
    expect(projectSourcePayment('domestic-payment', { amount: 1500, currencyCode: 'CZK', status: 'HELD', creditorAccountNumber: '123456', creditorBankCode: '0800', variableSymbol: '42', createdAt: 't' }))
      .toEqual({ service: 'domestic-payment', kind: 'domestic', status: 'HELD', amount: '1500', currency: 'CZK', counterAmount: null, counterCurrency: null, counterparty: '123456/0800', reference: 'VS 42', at: 't' })
    expect(projectSourcePayment('sepa-payment', { amount: '10', currencyCode: 'EUR', creditorName: 'ACME', creditorIban: 'DE1', endToEndId: 'E2E', createdAt: 't' }))
      .toMatchObject({ kind: 'sepa', counterparty: 'ACME · DE1', reference: 'E2E', currency: 'EUR' })
    expect(projectSourcePayment('sepa-instant', { amount: '5', currency: 'EUR', creditorIban: 'AT1', settledAt: 's', createdAt: 'c' }))
      .toMatchObject({ kind: 'sepa-instant', counterparty: 'AT1', at: 's', currency: 'EUR' })
    expect(projectSourcePayment('fx-service', { fromAmount: '100', fromCurrency: 'EUR', toAmount: '2500', toCurrency: 'CZK', appliedRate: '25', convertedAt: 't' }))
      .toMatchObject({ kind: 'fx', amount: '100', counterAmount: '2500', counterCurrency: 'CZK', counterparty: 'rate 25' })
    expect(projectSourcePayment('transaction-service', { amount: '1', currencyCode: 'CZK', description: 'd', referenceNumber: 'R', initiatedAt: 'i' }))
      .toMatchObject({ kind: 'transaction', counterparty: 'd', reference: 'R', at: 'i' })
  })

  it('refuses an unknown service or a non-object body', () => {
    expect(projectSourcePayment('ledger-service', {})).toBeNull()
    expect(projectSourcePayment('domestic-payment', [])).toBeNull()
    expect(projectSourcePayment('domestic-payment', { amount: 1 })?.counterparty).toBeNull()
  })
})

describe('k8sServiceOf', () => {
  it('derives the service from the module name aml-service records', () => {
    expect(k8sServiceOf('openbank-fx-service')).toBe('fx-service')
    expect(k8sServiceOf('fx-service')).toBeNull()
  })
})

describe('fetchSourcePayment', () => {
  it('asks only the recorded source — a 404 there is not a reason to guess elsewhere', async () => {
    const fetcher = vi.fn().mockResolvedValue(json({}, 404))
    vi.stubGlobal('fetch', fetcher)
    expect(await fetchSourcePayment('openbank-domestic-payment', id)).toBeNull()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(String(fetcher.mock.calls[0][0])).toContain(`/api/svc/domestic-payment/api/v1/domestic-payments/${id}`)
  })

  it('finds a legacy case by trying each rail in order, transaction-service first', async () => {
    const fetcher = vi.fn(async (url: string) => String(url).includes('/sepa-instant/') ? json({ amount: '9', currency: 'EUR' }) : json({}, 404))
    vi.stubGlobal('fetch', fetcher)
    const found = await fetchSourcePayment(null, id)
    expect(found).toMatchObject({ service: 'sepa-instant', amount: '9' })
    expect(fetcher.mock.calls.map(c => String(c[0]).split('/api/svc/')[1].split('/')[0])).toEqual(['transaction-service', 'domestic-payment', 'sepa-instant'])
    expect(LEGACY_ORDER.every(s => SOURCES[s])).toBe(true)
  })

  it('reads nothing for an unknown recorded source and survives a network failure', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', fetcher)
    expect(await fetchSourcePayment('openbank-unknown', id)).toBeNull()
    expect(await fetchSourcePayment('not-a-module', id)).toBeNull()
    expect(fetcher).not.toHaveBeenCalled()
    expect(await fetchSourcePayment(null, id)).toBeNull()
    expect(fetcher).toHaveBeenCalledTimes(LEGACY_ORDER.length)
  })

  it('stops when the caller aborts', async () => {
    const controller = new AbortController()
    vi.stubGlobal('fetch', vi.fn(async () => { controller.abort(); throw new DOMException('aborted', 'AbortError') }))
    await expect(fetchSourcePayment(null, id, controller.signal)).rejects.toThrow('aborted')
  })
})
