// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { signIn } from 'next-auth/react'
import AuthErrorPage from '@/app/auth/error/page'
import ForbiddenPage from '@/app/auth/forbidden/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

let query = new URLSearchParams()
const push = vi.fn()

vi.mock('next-auth/react', () => ({ signIn: vi.fn() }))
vi.mock('next/navigation', () => ({ useSearchParams: () => query, useRouter: () => ({ push }) }))

const renderInLanguage = (page: React.ReactNode) => render(<LanguageProvider>{page}</LanguageProvider>)

afterEach(() => {
  cleanup()
  localStorage.clear()
  query = new URLSearchParams()
  vi.clearAllMocks()
})

describe('authentication recovery experience', () => {
  it('explains an identity denial and retries with a validated return path', async () => {
    query = new URLSearchParams('error=AccessDenied&callbackUrl=%2Fpayments%3Fstate%3Dpending')
    vi.mocked(signIn).mockResolvedValue(undefined)
    renderInLanguage(<AuthErrorPage />)

    expect(screen.getByRole('alert')).toHaveTextContent('identity provider did not grant access')
    const retry = screen.getByRole('button', { name: 'Try secure sign-in again' })
    fireEvent.click(retry)

    await waitFor(() => expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/payments?state=pending' }))
    expect(retry).toHaveAttribute('aria-busy', 'true')
  })

  it('falls back to the dashboard when an external retry target is supplied', async () => {
    query = new URLSearchParams('callbackUrl=https%3A%2F%2Fattacker.example')
    vi.mocked(signIn).mockResolvedValue(undefined)
    renderInLanguage(<AuthErrorPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Try secure sign-in again' }))
    await waitFor(() => expect(signIn).toHaveBeenCalledWith('keycloak', { callbackUrl: '/dashboard' }))
  })

  it('teaches the access boundary without echoing an untrusted destination', () => {
    query = new URLSearchParams('path=%2F%2Fattacker.example%2Fprivate')
    renderInLanguage(<ForbiddenPage />)

    expect(screen.getByRole('heading', { name: 'This area is not in your role' })).toBeInTheDocument()
    expect(screen.getByText(/normal security control/)).toBeInTheDocument()
    expect(screen.queryByText('//attacker.example/private')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Return to your dashboard' }))
    expect(push).toHaveBeenCalledWith('/dashboard')
  })

  it('localizes the recovery guidance', () => {
    renderInLanguage(<ForbiddenPage />)
    fireEvent.click(screen.getByRole('button', { name: 'Přepnout do češtiny' }))
    expect(screen.getByRole('heading', { name: 'Tato oblast není součástí vaší role' })).toBeInTheDocument()
    expect(screen.getByText(/běžný bezpečnostní mechanismus/)).toBeInTheDocument()
  })
})
