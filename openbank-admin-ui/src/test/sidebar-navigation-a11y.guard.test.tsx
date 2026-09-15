// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Sidebar } from '@/components/layout/Sidebar'

const { prefetch, usePathname, useRouter } = vi.hoisted(() => ({
  prefetch: vi.fn(),
  usePathname: vi.fn(),
  useRouter: vi.fn(() => ({ prefetch })),
}))

vi.mock('next/navigation', () => ({ usePathname, useRouter }))
vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } } }),
}))
vi.mock('@/lib/i18n/LanguageContext', () => ({
  useLanguage: () => ({ language: 'en', t: (_cs: string, en: string) => en }),
}))

afterEach(() => vi.restoreAllMocks())

describe('sidebar navigation accessibility', () => {
  it.each([
    ['/iaops/flaky-test-hunter', 'Flaky Tests'],
    ['/temporal/flow', 'Workflow Flow'],
  ])('marks only the deepest matching route current for %s', (pathname, currentLabel) => {
    usePathname.mockReturnValue(pathname)

    render(<Sidebar />)

    const current = screen.getAllByRole('link', { current: 'page' })
    expect(current).toHaveLength(1)
    expect(current[0]).toHaveAccessibleName(currentLabel)
  })

  it('prefetches internal routes only after the operator signals intent', () => {
    usePathname.mockReturnValue('/dashboard')

    render(<Sidebar />)

    expect(prefetch).not.toHaveBeenCalled()

    const accounts = screen.getByRole('link', { name: 'Accounts' })
    fireEvent.mouseEnter(accounts)
    expect(prefetch).toHaveBeenLastCalledWith('/accounts')

    const transactions = screen.getByRole('link', { name: 'Transactions' })
    fireEvent.focus(transactions)
    expect(prefetch).toHaveBeenLastCalledWith('/transactions')
  })

  it('keeps independently served tools outside the App Router prefetch path', () => {
    usePathname.mockReturnValue('/dashboard')

    render(<Sidebar />)

    const grafana = screen.getByRole('link', { name: 'Grafana' })
    fireEvent.mouseEnter(grafana)
    fireEvent.focus(grafana)

    expect(prefetch).not.toHaveBeenCalled()
  })
})
