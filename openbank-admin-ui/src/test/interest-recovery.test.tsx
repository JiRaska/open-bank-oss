// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InterestPage from '@/app/interest/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => children,
}))

const FIRST_ACCRUAL = {
  id: 'accrual-1',
  accountId: 'CZ6508000000001234567899',
  accrualDate: '2026-08-31',
  accruedAmount: 10.25,
  currency: 'CZK',
  rate: 0.0325,
  dayCount: 'ACT_365',
  status: 'ACCRUING',
}

const REFRESHED_ACCRUAL = {
  ...FIRST_ACCRUAL,
  id: 'accrual-2',
  accountId: 'CZ6508000000009876543210',
  accruedAmount: 11.5,
}

const FINAL_ACCRUAL = {
  ...REFRESHED_ACCRUAL,
  id: 'accrual-3',
  accountId: 'CZ6508000000001111222233',
  accruedAmount: 12.75,
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

async function renderPage() {
  await act(async () => {
    render(<LanguageProvider initialLanguage="en"><InterestPage /></LanguageProvider>)
  })
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('interest snapshot recovery', () => {
  it('keeps the last successful rows visible after a failed refresh and releases single-flight after success', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, [FIRST_ACCRUAL]))
      .mockResolvedValueOnce(jsonResponse(500, { error: 'temporarily unavailable' }))
      .mockResolvedValueOnce(jsonResponse(200, [REFRESHED_ACCRUAL]))
      .mockResolvedValueOnce(jsonResponse(200, [FINAL_ACCRUAL]))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    expect(await screen.findByText(FIRST_ACCRUAL.accountId)).toBeVisible()

    const refresh = screen.getByRole('button', { name: 'Refresh interest records' })
    fireEvent.click(refresh)
    fireEvent.click(refresh)

    const stale = await screen.findByRole('status', { name: 'Interest data freshness' })
    expect(stale).toHaveTextContent('showing the last successful snapshot')
    expect(stale).toHaveTextContent('Last successful load')
    expect(screen.getByText(FIRST_ACCRUAL.accountId)).toBeVisible()
    expect(screen.getByText('10.2500')).toBeVisible()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    fireEvent.click(screen.getByRole('button', { name: 'Retry loading interest records' }))

    expect(await screen.findByText(REFRESHED_ACCRUAL.accountId)).toBeVisible()
    await waitFor(() => expect(screen.queryByRole('status', { name: 'Interest data freshness' })).not.toBeInTheDocument())
    expect(fetchMock).toHaveBeenCalledTimes(3)

    fireEvent.click(screen.getByRole('button', { name: 'Refresh interest records' }))

    expect(await screen.findByText(FINAL_ACCRUAL.accountId)).toBeVisible()
    expect(screen.queryByText(REFRESHED_ACCRUAL.accountId)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('shows an initial failure without synthetic zero statistics or rows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse(500, { error: 'temporarily unavailable' }),
    ))

    await renderPage()

    expect(await screen.findByRole('button', { name: 'Retry loading interest records' })).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent('Failed to load: Interest records')
    expect(screen.queryByText('Total records')).not.toBeInTheDocument()
    expect(screen.queryByText(FIRST_ACCRUAL.accountId)).not.toBeInTheDocument()
  })

  it.each([
    { status: 401, body: { error: 'unauthorized' }, title: 'Session expired' },
    { status: 403, body: { error: 'forbidden' }, title: 'Access denied' },
  ])('keeps a retained privileged snapshot blocked through an HTTP $status retry', async ({ status, body, title }) => {
    let resolveRetry!: (response: Response) => void
    const retryResponse = new Promise<Response>(resolve => { resolveRetry = resolve })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, [FIRST_ACCRUAL]))
      .mockResolvedValueOnce(jsonResponse(status, body))
      .mockReturnValueOnce(retryResponse)
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    expect(await screen.findByText(FIRST_ACCRUAL.accountId)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh interest records' }))

    const blocked = await screen.findByRole('alert')
    expect(blocked).toHaveTextContent(title)
    expect(blocked.querySelector('[role="status"], [role="alert"]')).toBeNull()
    expect(screen.queryByText('interest-service :8125')).not.toBeInTheDocument()
    expect(screen.queryByTitle('interest-service is not responding')).not.toBeInTheDocument()
    expect(screen.queryByTitle('interest-service is up')).not.toBeInTheDocument()
    expect(screen.queryByText(FIRST_ACCRUAL.accountId)).not.toBeInTheDocument()
    expect(screen.queryByText('Total records')).not.toBeInTheDocument()
    expect(screen.queryByRole('status', { name: 'Interest data freshness' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading interest records' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(screen.getByRole('alert')).toHaveTextContent(title)
    expect(screen.queryByText(FIRST_ACCRUAL.accountId)).not.toBeInTheDocument()
    expect(screen.queryByText(REFRESHED_ACCRUAL.accountId)).not.toBeInTheDocument()
    expect(screen.queryByText('Total records')).not.toBeInTheDocument()

    await act(async () => { resolveRetry(jsonResponse(200, [REFRESHED_ACCRUAL])) })

    expect(await screen.findByText(REFRESHED_ACCRUAL.accountId)).toBeVisible()
    expect(screen.getByText('Total records')).toBeVisible()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByTitle('interest-service is up')).toHaveTextContent('interest-service :8125')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('announces one refreshing state during a wake retry and replaces the snapshot on success', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, [FIRST_ACCRUAL]))
      .mockResolvedValueOnce(jsonResponse(503, { error: 'scaled_to_zero' }))
      .mockResolvedValueOnce(jsonResponse(200, [REFRESHED_ACCRUAL]))
    vi.stubGlobal('fetch', fetchMock)

    await renderPage()
    expect(await screen.findByText(FIRST_ACCRUAL.accountId)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh interest records' }))

    expect(await screen.findByText('Refreshing interest records; the last snapshot remains available.')).toBeVisible()
    expect(screen.queryByRole('status', { name: 'Interest data freshness' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('status')).toHaveLength(1)

    expect(await screen.findByText(REFRESHED_ACCRUAL.accountId, {}, { timeout: 6_000 })).toBeVisible()
    expect(screen.queryByText(FIRST_ACCRUAL.accountId)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  }, 10_000)
})
