// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ReferralProgramsPage from '@/app/campaigns/referrals/page'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => children,
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('MGM programme catalogue', () => {
  it('shows verified conversion from the deduplicated funnel beside the immutable programme', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).endsWith('/funnel')
      ? Response.json({ state: 'ok', items: [{ programId: 'p-1', qualifiedInvites: 4, rewardRequests: 3, rewardedInvites: 2, failedRewards: 1, reversedRewards: 0, requestedRewardAmount: 1500, currency: 'CZK' }] })
      : Response.json({ state: 'ok', items: [{ id: 'p-1', name: 'friends', version: 2, rewardAmount: 500, currency: 'CZK', qualifyingEvent: 'FIRST_CARD_PAYMENT', attributionWindowEndsAt: '2027-01-01T00:00:00Z', status: 'PUBLISHED', checker: 'checker' }] })))

    render(React.createElement(LanguageProvider, null, React.createElement(ReferralProgramsPage)))

    await waitFor(() => expect(screen.getByText('friends')).toBeTruthy())
    const funnel = document.querySelector('[data-referral-funnel="p-1"]')
    expect(funnel?.textContent).toContain('4')
    expect(funnel?.textContent).toContain('2')
    expect(funnel?.textContent).toContain('50 %')
  })

  it('does not render unavailable measurement as zero conversion', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).endsWith('/funnel')
      ? Response.json({ state: 'unavailable', items: [] })
      : Response.json({ state: 'ok', items: [{ id: 'p-1', name: 'friends', version: 2, rewardAmount: 500, currency: 'CZK', qualifyingEvent: 'FIRST_CARD_PAYMENT', attributionWindowEndsAt: '2027-01-01T00:00:00Z', status: 'PUBLISHED', checker: 'checker' }] })))

    render(React.createElement(LanguageProvider, null, React.createElement(ReferralProgramsPage)))
    await waitFor(() => expect(screen.getByText('Measurement is temporarily unavailable.')).toBeTruthy())
    expect(document.querySelector('[data-referral-funnel="p-1"]')?.textContent).not.toContain('0 %')
  })
})
