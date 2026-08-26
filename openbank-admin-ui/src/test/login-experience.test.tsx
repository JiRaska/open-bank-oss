// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { signIn } from 'next-auth/react'
import LoginPage from '@/app/auth/login/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { safeCallbackPath } from '@/lib/auth/safeCallbackPath'

let query = new URLSearchParams()

vi.mock('next-auth/react', () => ({ signIn: vi.fn() }))
vi.mock('next/navigation', () => ({ useSearchParams: () => query }))

const renderPage = () => render(<LanguageProvider><LoginPage /></LanguageProvider>)

afterEach(() => {
  cleanup()
  localStorage.clear()
  query = new URLSearchParams()
  vi.clearAllMocks()
})

describe('safeCallbackPath', () => {
  it.each([
    [null, '/dashboard'],
    ['', '/dashboard'],
    ['https://attacker.example', '/dashboard'],
    ['//attacker.example/path', '/dashboard'],
    ['/\\attacker.example/path', '/dashboard'],
    ['/accounts?state=open#detail', '/accounts?state=open#detail'],
  ])('maps %s to %s', (candidate, expected) => {
    expect(safeCallbackPath(candidate)).toBe(expected)
  })
})

describe('login experience', () => {
  it('explains the workspace and signs in through the single governed provider', async () => {
    query = new URLSearchParams('callbackUrl=%2Fpayments%3Fstatus%3Dpending')
    vi.mocked(signIn).mockResolvedValue(undefined)
    renderPage()

    expect(screen.getByRole('heading', { name: 'Operate with confidence.' })).toBeInTheDocument()
    expect(screen.getByText('Decisions with context')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: 'Continue with Keycloak SSO' })
    fireEvent.click(button)

    await waitFor(() => expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/payments?status=pending' }))
    expect(button).toHaveAttribute('aria-busy', 'true')
  })

  it('switches language and presents a clear expired-session recovery', () => {
    query = new URLSearchParams('error=SessionExpired')
    renderPage()

    expect(screen.getByRole('alert')).toHaveTextContent('Your session expired')
    fireEvent.click(screen.getByRole('button', { name: 'Přepnout do češtiny' }))
    expect(screen.getByRole('heading', { name: 'Řiďte banku s jistotou.' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Vaše relace vypršela')
  })
})
