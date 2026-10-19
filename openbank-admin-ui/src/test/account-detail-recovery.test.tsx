// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AccountDetailPage from '@/app/accounts/[id]/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { accountApi } from '@/lib/api'

vi.mock('next/navigation', () => ({ useParams: () => ({ id: 'account-7' }) }))
vi.mock('@/components/auth/AuthGuard', () => ({ Can: ({ children }: { children: ReactNode }) => children }))
vi.mock('@/components/entities/EntityChip', () => ({ EntityChip: () => null }))
vi.mock('@/lib/api', () => ({ accountApi: { getBalance: vi.fn() } }))

const ACCOUNT = {
  id: 'account-7',
  accountNumber: 'TEST-ACCOUNT-0007',
  accountType: 'CURRENT',
  partyId: 'party-2',
  productId: 'product-1',
  currencyCode: 'CZK',
  status: 'ACTIVE',
  openedAt: '2026-09-10T08:30:00Z',
}

const BALANCE = {
  accountId: ACCOUNT.id,
  availableBalance: 100,
  currentBalance: 100,
  reservedBalance: 0,
  pendingBalance: 0,
  currencyCode: ACCOUNT.currencyCode,
  lastUpdatedAt: '2026-09-10T08:31:00Z',
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function renderPage() {
  render(<LanguageProvider initialLanguage="en"><AccountDetailPage /></LanguageProvider>)
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('account detail recovery', () => {
  it('explains a genuine missing account and offers retry and navigation back', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(404, { error: 'not_found' })))
    vi.mocked(accountApi.getBalance).mockResolvedValue(BALANCE as never)

    renderPage()

    expect(await screen.findByText('Nothing found')).toBeVisible()
    expect(screen.queryByText('HTTP 404')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry loading account' })).toBeVisible()
    expect(screen.getByRole('link', { name: 'Back to accounts' })).toHaveAttribute('href', '/accounts')
  })

  it('keeps a service failure distinct from not-found and recovers after retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(503, { error: 'temporarily_unavailable' }))
      .mockResolvedValueOnce(jsonResponse(200, ACCOUNT))
    vi.stubGlobal('fetch', fetchMock)
    vi.mocked(accountApi.getBalance).mockResolvedValue(BALANCE as never)

    renderPage()

    expect(await screen.findByText('Failed to load: account detail')).toBeVisible()
    expect(screen.queryByText('Nothing found')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading account' }))

    expect(await screen.findByRole('heading', { name: ACCOUNT.accountNumber })).toBeVisible()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  it('explains when the service returns account evidence that fails validation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { ...ACCOUNT, id: 'another-account' })))
    vi.mocked(accountApi.getBalance).mockResolvedValue(BALANCE as never)

    renderPage()

    expect(await screen.findByText('The service returned unverifiable account data.')).toBeVisible()
    expect(screen.queryByText('HTTP 200')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry loading account' })).toBeVisible()
  })
})
