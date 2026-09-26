import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import AccountsPage from '@/app/accounts/page'

vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: React.ReactNode }) => <>{children}</> }))

const account = {
  id: 'account-1', accountNumber: '1234567890', accountType: 'CURRENT', currencyCode: 'CZK',
  status: 'ACTIVE', partyId: 'party-1', openedAt: '2026-08-01T12:00:00Z',
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('account search accessibility', () => {
  it('does not leave a dangling help reference after results replace the search guidance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: [account], pagination: {} }), {
      status: 200, headers: { 'content-type': 'application/json' },
    })))
    render(React.createElement(LanguageProvider, null, React.createElement(AccountsPage)))

    const query = screen.getByLabelText('Search accounts by number, IBAN, or Party ID')
    expect(query).toHaveAttribute('aria-describedby', 'accounts-query-help')
    fireEvent.change(query, { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search accounts' }))

    await screen.findByText('1234567890')
    expect(query).not.toHaveAttribute('aria-describedby')
    expect(document.getElementById('accounts-query-help')).toBeNull()
  })

  it('follows the backend cursor and appends unique accounts', async () => {
    const secondAccount = { ...account, id: 'account-2', accountNumber: '1234567899' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: [account],
        pagination: { limit: 25, hasNextPage: true, nextCursor: 'next page/+=' },
      }), { status: 200, headers: { 'content-type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: [account, secondAccount],
        pagination: { limit: 25, hasNextPage: false },
      }), { status: 200, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    render(React.createElement(LanguageProvider, null, React.createElement(AccountsPage)))

    fireEvent.change(screen.getByLabelText('Search accounts by number, IBAN, or Party ID'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search accounts' }))
    await screen.findByText('1234567890')

    fireEvent.click(screen.getByRole('button', { name: 'Load more accounts' }))
    await screen.findByText('1234567899')

    expect(fetchMock).toHaveBeenLastCalledWith(
      expect.stringContaining('cursor=next%20page%2F%2B%3D'),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(screen.getAllByText('1234567890')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Load more accounts' })).toBeNull()
  })

  it('preserves the loaded page when the next cursor fails', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: [account],
        pagination: { limit: 25, hasNextPage: true, nextCursor: 'next' },
      }), { status: 200, headers: { 'content-type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('', { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)
    render(React.createElement(LanguageProvider, null, React.createElement(AccountsPage)))

    fireEvent.change(screen.getByLabelText('Search accounts by number, IBAN, or Party ID'), { target: { value: '12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search accounts' }))
    await screen.findByText('1234567890')
    fireEvent.click(screen.getByRole('button', { name: 'Load more accounts' }))

    await screen.findByText('Loaded accounts were preserved. Try loading the next page again.')
    expect(screen.getByText('1234567890')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Load more accounts' })).toBeEnabled()
  })
})
