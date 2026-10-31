// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Regression: the sidebar nav must paint ONCE, in its final permission-filtered
// shape. The server seeds the client with the operator's roles (RootLayout reads
// the session cookie); before that, the nav rendered with roles=[] while
// useSession() was loading and re-painted the whole console when the session
// landed — the admin-operator-access journey measured a stable 0.12+ workspace
// CLS from exactly that re-paint (Dashboard jumping ~164px).

import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Sidebar } from '@/components/layout/Sidebar'

const { usePathname, useSessionState, useInitialRolesState } = vi.hoisted(() => ({
  usePathname: vi.fn(),
  useSessionState: vi.fn(),
  useInitialRolesState: vi.fn(),
}))

vi.mock('next/navigation', () => ({ usePathname }))
vi.mock('next-auth/react', () => ({ useSession: () => useSessionState() }))
vi.mock('@/components/auth/InitialRolesContext', () => ({ useInitialRoles: () => useInitialRolesState() }))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

describe('sidebar single-paint nav', () => {
  it('paints the server-seeded operator nav while the client session is still loading', () => {
    usePathname.mockReturnValue('/system/tests')
    // The server rendered this shell with the operator's roles; the client
    // useSession() round-trip has not resolved yet.
    useInitialRolesState.mockReturnValue(['ROLE_OPERATOR'])
    useSessionState.mockReturnValue({ data: undefined, status: 'loading' })

    render(<Sidebar />)

    // The final permission-filtered nav is present on the FIRST paint — no empty
    // intermediate shape, nothing to re-paint under the user when session lands.
    expect(screen.getAllByRole('link', { name: /Test Intelligence/ }).length).toBeGreaterThan(0)
  })

  it('keeps the same nav when the client session resolves with identical roles', () => {
    usePathname.mockReturnValue('/system/tests')
    useInitialRolesState.mockReturnValue(['ROLE_OPERATOR'])
    useSessionState.mockReturnValue({ data: { user: { roles: ['ROLE_OPERATOR'] } }, status: 'authenticated' })

    const { container } = render(<Sidebar />)
    const nav = container.querySelector('nav')

    expect(nav?.textContent ?? '').toContain('Test Intelligence')
  })
})
