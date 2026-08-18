// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import TransactionsPage from '@/app/transactions/page'

afterEach(() => {
  cleanup()
  localStorage.clear()
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
})
