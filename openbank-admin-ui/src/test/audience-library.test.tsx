// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import SegmentsPage from '@/app/segments/page'
import { SessionProvider } from 'next-auth/react'

describe('audience library', () => {
  it('uses the real segment preview and carries its version into campaign authoring', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/preview')) return { ok: true, json: async () => ({ state: 'ok', size: 1240, asOf: '2026-08-13T10:00:00Z' }) }
      return { ok: true, json: async () => ({ state: 'ok', items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'] }] }) }
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    const { container } = render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    await waitFor(() => expect(screen.getByText('Active customers')).toBeTruthy())

    const start = container.querySelector('[data-use-audience="actives@1"]') as HTMLAnchorElement
    expect(start.href).toContain('/campaigns/new?audience=actives%401')
    fireEvent.click(container.querySelector('[data-audience-count="actives@1"]')!)
    await waitFor(() => expect(container.querySelector('[data-audience-size="actives@1"]')?.textContent).toContain('1,240'))
  })
})
