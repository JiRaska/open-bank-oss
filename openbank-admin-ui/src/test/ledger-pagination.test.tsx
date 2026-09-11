// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import LedgerPage from '@/app/ledger/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

const firstPage = {
  data: [{ id: 'entry-1', entryNumber: 1, transactionId: 'transaction-1', entryDate: '2026-08-01', valueDate: '2026-08-01', status: 'POSTED', lines: [], description: 'First page', createdAt: '2026-08-01T12:00:00Z', synthetic: false }],
  pagination: { limit: 20, hasNextPage: true, nextCursor: 'cursor-1' },
}
const secondPage = {
  data: [{ id: 'entry-2', entryNumber: 2, transactionId: 'transaction-2', entryDate: '2026-08-02', valueDate: '2026-08-02', status: 'POSTED', lines: [], description: 'Second page', createdAt: '2026-08-02T12:00:00Z', synthetic: true }],
  pagination: { limit: 20, hasNextPage: false },
}

function renderPage() {
  return render(React.createElement(LanguageProvider, null, React.createElement(LedgerPage)))
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('General Ledger pagination', () => {
  it('labels the date filters and exposes journal-line disclosure semantics', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response(firstPage))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    expect(screen.getByLabelText('From')).toHaveAttribute('id', 'ledger-from-date')
    expect(screen.getByLabelText('To')).toHaveAttribute('id', 'ledger-to-date')
    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    await screen.findByText('First page')
    const disclosure = screen.getByRole('button', { name: 'Show journal lines' })
    expect(disclosure).toHaveAttribute('aria-expanded', 'false')
    expect(disclosure).toHaveAttribute('aria-controls', 'ledger-entry-entry-1')
  })

  it('forwards the server cursor and appends the next journal page', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(firstPage))
      .mockResolvedValueOnce(response(secondPage))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    await screen.findByText('First page')
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    await screen.findByText('Second page')
    expect(screen.getByText('First page')).toBeTruthy()
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('cursor=cursor-1')
    expect(String(fetchMock.mock.calls[1]?.[0])).toContain('limit=20')
  })

  it('keeps the first page visible and explains a next-page failure', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(firstPage))
      .mockRejectedValueOnce(new TypeError('network down'))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    await screen.findByText('First page')
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('next page could not be loaded'))
    expect(screen.getByText('First page')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Load more' })).toBeTruthy()
  })

  it('maps an unknown network failure to the reachable-service recovery state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('network down')))
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Ledger-service is not responding'))
  })

  it('rejects a duplicate next page without erasing the verified snapshot', async () => {
    const duplicatePage = { data: [firstPage.data[0]], pagination: { limit: 20, hasNextPage: false } }
    const fetchMock = vi.fn().mockResolvedValueOnce(response(firstPage)).mockResolvedValueOnce(response(duplicatePage))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    await screen.findByText('First page')
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('next page could not be loaded'))
    expect(screen.getAllByText('First page')).toHaveLength(1)
  })

  it('labels a retained snapshot after the operator changes the date window and locks pagination', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(firstPage)))
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    await screen.findByText('First page')

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-01' } })
    expect(screen.getByRole('status').textContent).toContain('visible snapshot belongs to')
    expect(screen.getByRole('button', { name: 'Load more' })).toBeDisabled()
  })

  it('does not commit a search superseded by a date-window change', async () => {
    let resolveFetch!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValueOnce(new Promise<Response>(resolve => { resolveFetch = resolve })))
    renderPage()

    fireEvent.click(screen.getByRole('button', { name: 'Load Entries' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-01' } })
    resolveFetch(response(firstPage))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Load Entries' })).not.toBeDisabled())
    expect(screen.queryByText('First page')).toBeNull()
  })
})
