// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { PrivacyContent } from '@/app/privacy/PrivacyContent'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const renderPage = () => render(<LanguageProvider><PrivacyContent /></LanguageProvider>)

afterEach(() => {
  cleanup()
  localStorage.clear()
})

describe('public privacy experience', () => {
  it('explains the complete operator-data journey without weakening the legal notice', () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Know what happens to your operator data.' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'The data journey' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Identity' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Session' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Audit evidence' })).toBeInTheDocument()
    expect(screen.getByText(/EBA ICT Risk Guidelines and PSD2/)).toBeInTheDocument()
    expect(screen.getByText(/no marketing or tracking cookies/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /hello@open-bank.tech/ })).toHaveAttribute('href', 'mailto:hello@open-bank.tech')
    expect(screen.getByRole('link', { name: /security@open-bank.tech/ })).toHaveAttribute('href', 'mailto:security@open-bank.tech')
  })

  it('switches the full notice to Czech and preserves the sign-in return', () => {
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Přepnout do češtiny' }))

    expect(screen.getByRole('heading', { name: 'Víte, co se děje s údaji operátora.' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Cesta údajů' })).toBeInTheDocument()
    expect(screen.getByText(/regulatorní povinností uchování/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Zpět k bezpečnému přihlášení/ })).toHaveAttribute('href', '/auth/login')
  })
})
