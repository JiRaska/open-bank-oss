// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import NewReferralProgramPage from '@/app/campaigns/referrals/new/page'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

function renderPage() {
  return render(
    <LanguageProvider>
      <NewReferralProgramPage />
    </LanguageProvider>,
  )
}

function fillValidForm() {
  fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'friends-autumn' } })
  fireEvent.change(screen.getByTestId('field-reward'), { target: { value: '500' } })
}

describe('MGM program authoring page', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('submits the contract fields and confirms the draft awaits a different publisher', async () => {
    const upstream = vi.fn(async () => Response.json({ state: 'ok', id: 'p-1' }))
    vi.stubGlobal('fetch', upstream)
    renderPage()
    fillValidForm()

    fireEvent.click(screen.getByTestId('referral-submit'))

    await waitFor(() => expect(screen.getByTestId('referral-draft-created')).toBeTruthy())
    expect(upstream).toHaveBeenCalledWith('/api/referral-programs', expect.objectContaining({ method: 'POST' }))
    const body = JSON.parse((upstream.mock.calls[0][1] as RequestInit).body as string)
    expect(body).toMatchObject({
      name: 'friends-autumn',
      version: 1,
      rewardAmount: 500,
      currency: 'CZK',
      qualifyingEvent: 'account.opened',
    })
    expect(body.attributionWindowEndsAt).toMatch(/^\d{4}-\d{2}-\d{2}T23:59:59/)
  })

  it('blocks submit with a client-side error when the name is not a valid slug', async () => {
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    renderPage()

    fireEvent.change(screen.getByTestId('field-name'), { target: { value: 'Bad Name!' } })
    fireEvent.change(screen.getByTestId('field-reward'), { target: { value: '500' } })
    fireEvent.click(screen.getByTestId('referral-submit'))

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    expect(upstream).not.toHaveBeenCalled()
  })

  it('shows the upstream rejection reason instead of a generic failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Response.json(
      { state: 'rejected', message: 'program friends-autumn@1 already exists' },
    )))
    renderPage()
    fillValidForm()

    fireEvent.click(screen.getByTestId('referral-submit'))

    await waitFor(() => expect(screen.getByTestId('referral-submit-error')).toBeTruthy())
    expect(screen.getByTestId('referral-submit-error').textContent)
      .toContain('program friends-autumn@1 already exists')
  })
})
