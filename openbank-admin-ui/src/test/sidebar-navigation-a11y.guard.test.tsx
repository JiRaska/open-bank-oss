// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Sidebar } from '@/components/layout/Sidebar'

const { usePathname } = vi.hoisted(() => ({ usePathname: vi.fn() }))

vi.mock('next/navigation', () => ({ usePathname }))
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
})
