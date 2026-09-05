// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import TransactionsPage from '@/app/transactions/page'

function transaction(index: number) {
  return {
    id: `transaction-${index}`,
    referenceNumber: `TXN-${String(index).padStart(3, '0')}`,
    type: 'CREDIT',
    amount: 100 + index,
    currencyCode: 'CZK',
    status: 'COMPLETED',
    valueDate: '2026-09-01',
    bookingDate: '2026-09-01',
    initiatedAt: '2026-09-01T08:00:00Z',
  }
}

function resultResponse(data: ReturnType<typeof transaction>[], offset: number) {
  return new Response(JSON.stringify({ data, count: data.length, limit: 50, offset }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('Transaction search pagination', () => {
  it('requests the next 50-row page with offset 50', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => transaction(index))
    const secondPage = [transaction(50)]
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input), 'https://admin.openbank.example')
      const offset = Number(url.searchParams.get('offset'))
      const data = offset === 50 ? secondPage : firstPage
      return resultResponse(data, offset)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(React.createElement(LanguageProvider, null, React.createElement(TransactionsPage)))
    fireEvent.click(screen.getByRole('button', { name: 'Search transactions' }))

    await screen.findByText('TXN-049')
    expect(screen.getByText('Transactions 1–50')).toBeVisible()
    expect(screen.queryByText('Found:')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous transaction page' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Next transaction page' }))

    await screen.findByText('TXN-050')
    expect(screen.getByText('Transactions 51–51')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Next transaction page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Previous transaction page' })).toBeEnabled()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[0][0])).toContain('limit=50&offset=0')
    expect(String(fetchMock.mock.calls[1][0])).toContain('limit=50&offset=50')
  })

  it('resets to offset 0 when a changed filter is searched', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => transaction(index))
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input), 'https://admin.openbank.example')
      const offset = Number(url.searchParams.get('offset'))
      if (url.searchParams.get('iban')) return resultResponse([transaction(99)], offset)
      return resultResponse(offset === 50 ? [transaction(50)] : firstPage, offset)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(React.createElement(LanguageProvider, null, React.createElement(TransactionsPage)))
    fireEvent.click(screen.getByRole('button', { name: 'Search transactions' }))
    await screen.findByText('TXN-049')
    fireEvent.click(screen.getByRole('button', { name: 'Next transaction page' }))
    await screen.findByText('TXN-050')

    fireEvent.change(screen.getByLabelText('Filter by IBAN'), { target: { value: 'CZ6508000000192000145399' } })
    expect(screen.getByRole('button', { name: 'Previous transaction page' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next transaction page' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Search transactions' }))

    await screen.findByText('TXN-099')
    const latestUrl = String(fetchMock.mock.calls.at(-1)?.[0])
    expect(latestUrl).toContain('iban=CZ6508000000192000145399')
    expect(latestUrl).toContain('limit=50&offset=0')
  })

  it('does not let a superseded page response replace a newer search', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => transaction(index))
    let resolveNextPage: ((response: Response) => void) | undefined
    const nextPage = new Promise<Response>(resolve => { resolveNextPage = resolve })
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input), 'https://admin.openbank.example')
      if (url.searchParams.get('accountId') === 'new-account') return resultResponse([transaction(99)], 0)
      if (url.searchParams.get('offset') === '50') return nextPage
      return resultResponse(firstPage, 0)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(React.createElement(LanguageProvider, null, React.createElement(TransactionsPage)))
    fireEvent.click(screen.getByRole('button', { name: 'Search transactions' }))
    await screen.findByText('TXN-049')
    fireEvent.click(screen.getByRole('button', { name: 'Next transaction page' }))

    const account = screen.getByLabelText('Search by account ID')
    fireEvent.change(account, { target: { value: 'new-account' } })
    fireEvent.keyDown(account, { key: 'Enter' })
    await screen.findByText('TXN-099')

    await act(async () => {
      resolveNextPage?.(resultResponse([transaction(50)], 50))
      await nextPage
    })
    expect(screen.getByText('TXN-099')).toBeVisible()
    expect(screen.queryByText('TXN-050')).not.toBeInTheDocument()
    expect(screen.getByText('Transactions 1–1')).toBeVisible()
  })
})
