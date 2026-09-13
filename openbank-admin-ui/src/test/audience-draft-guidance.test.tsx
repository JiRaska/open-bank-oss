// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import NewAudiencePage from '@/app/segments/new/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const push = vi.fn()

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  push.mockReset()
})

function mount() {
  return render(<LanguageProvider><NewAudiencePage /></LanguageProvider>)
}

describe('audience draft guidance', () => {
  it('explains invalid fields where the operator can correct them', async () => {
    const user = userEvent.setup()
    mount()

    const name = screen.getByLabelText('Name')
    await user.type(name, 'Wrong Name')
    await user.tab()
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/Start with a letter or number/)).toBeVisible()

    const tenure = screen.getByRole('textbox', { name: /Minimum relationship age/ })
    await user.type(tenure, '-2')
    await user.tab()
    expect(tenure).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/whole non-negative number/)).toBeVisible()
  })

  it('turns a conflict into actionable copy without exposing the backend body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'duplicate key value violates audiences_name_key' }), { status: 409 })))
    const user = userEvent.setup()
    mount()

    await user.type(screen.getByLabelText('Name'), 'new-savers')
    await user.click(screen.getByRole('button', { name: 'Create audience draft' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('An audience with this name already exists')
    expect(document.body).not.toHaveTextContent('duplicate key')
    expect(screen.getByLabelText('Name')).toHaveValue('new-savers')
    expect(push).not.toHaveBeenCalled()
  })

  it('keeps valid entries during an outage and navigates only after success', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('private upstream hostname'))
      .mockResolvedValueOnce(new Response(JSON.stringify({ name: 'new-savers', version: 1 }), { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    mount()

    await user.type(screen.getByLabelText('Name'), 'new-savers')
    const submit = screen.getByRole('button', { name: 'Create audience draft' })
    await user.click(submit)
    expect(await screen.findByRole('alert')).toHaveTextContent('Your entries remain in the form')
    expect(document.body).not.toHaveTextContent('private upstream hostname')
    expect(screen.getByLabelText('Name')).toHaveValue('new-savers')

    await user.click(submit)
    expect(push).toHaveBeenCalledWith('/segments')
  })
})
