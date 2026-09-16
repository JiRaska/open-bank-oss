// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import TransactionsPage from '@/app/transactions/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('Transaction search UX', () => {
  it('exposes the optional search filters as an accessible disclosure', () => {
    render(React.createElement(LanguageProvider, null, React.createElement(TransactionsPage)))

    const filters = screen.getByRole('button', { name: 'Filters' })
    expect(filters).toHaveAttribute('aria-expanded', 'false')
    expect(filters).toHaveAttribute('aria-controls', 'transaction-search-filters')

    fireEvent.click(filters)
    expect(filters).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById('transaction-search-filters')).toBeTruthy()
    expect(screen.getByLabelText('Reference number')).toHaveAttribute('id', 'transaction-reference')
    expect(screen.getByLabelText('Amount from (CZK)')).toHaveAttribute('placeholder', '0.00')
  })

  it('explains reversed date and amount ranges without sending a search request', () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    render(React.createElement(LanguageProvider, null, React.createElement(TransactionsPage)))
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))

    const dateFrom = screen.getByLabelText('Date from')
    const dateTo = screen.getByLabelText('Date to')
    fireEvent.change(dateFrom, { target: { value: '2026-09-16' } })
    fireEvent.change(dateTo, { target: { value: '2026-09-01' } })
    expect(dateFrom).toHaveAttribute('aria-invalid', 'true')
    expect(dateTo).toHaveAttribute('aria-describedby', 'transaction-date-range-error')
    expect(screen.getByRole('alert')).toHaveTextContent('The start date must be on or before the end date.')
    expect(screen.getByRole('button', { name: 'Search transactions' })).toBeDisabled()
    fireEvent.keyDown(screen.getByLabelText('Search by account ID'), { key: 'Enter' })
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/transactions/search'))).toBe(false)

    fireEvent.change(dateTo, { target: { value: '2026-09-30' } })
    const amountMin = screen.getByLabelText('Amount from (CZK)')
    const amountMax = screen.getByLabelText('Amount to (CZK)')
    fireEvent.change(amountMin, { target: { value: '200' } })
    fireEvent.change(amountMax, { target: { value: '100' } })
    expect(amountMin).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('The minimum amount cannot exceed the maximum amount.')
    expect(screen.getByRole('button', { name: 'Search transactions' })).toBeDisabled()
    fireEvent.keyDown(screen.getByLabelText('Search by account ID'), { key: 'Enter' })
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/transactions/search'))).toBe(false)
  })
})
