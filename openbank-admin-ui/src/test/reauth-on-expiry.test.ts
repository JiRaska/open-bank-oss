// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createElement } from 'react'
import { render, cleanup } from '@testing-library/react'

// Mock next-auth/react so we can drive the session state and observe signIn().
const signInMock = vi.fn()
let mockSession: { user?: { error?: string; accessToken?: string } } | null = null
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: mockSession }),
  signIn: (...args: unknown[]) => signInMock(...args),
}))

import { ReauthOnExpiry } from '@/components/auth/ReauthOnExpiry'

describe('ReauthOnExpiry — re-login on refresh failure', () => {
  beforeEach(() => {
    signInMock.mockClear()
    mockSession = null
    cleanup()
  })

  it('kicks off Keycloak sign-in when the session refresh has failed', () => {
    mockSession = { user: { error: 'RefreshAccessTokenError' } }
    render(createElement(ReauthOnExpiry))
    expect(signInMock).toHaveBeenCalledTimes(1)
    expect(signInMock).toHaveBeenCalledWith('keycloak')
  })

  it('does NOT sign in for a healthy authenticated session', () => {
    mockSession = { user: { accessToken: 'valid-token' } }
    render(createElement(ReauthOnExpiry))
    expect(signInMock).not.toHaveBeenCalled()
  })

  it('does NOT sign in when there is no session yet (loading / anonymous)', () => {
    mockSession = null
    render(createElement(ReauthOnExpiry))
    expect(signInMock).not.toHaveBeenCalled()
  })

  it('does NOT sign in for an unrelated session error', () => {
    mockSession = { user: { error: 'SomeOtherError' } }
    render(createElement(ReauthOnExpiry))
    expect(signInMock).not.toHaveBeenCalled()
  })

  it('fires at most once across re-renders (the double-fire guard holds)', () => {
    mockSession = { user: { error: 'RefreshAccessTokenError' } }
    const { rerender } = render(createElement(ReauthOnExpiry))
    rerender(createElement(ReauthOnExpiry))
    rerender(createElement(ReauthOnExpiry))
    expect(signInMock).toHaveBeenCalledTimes(1)
  })
})
