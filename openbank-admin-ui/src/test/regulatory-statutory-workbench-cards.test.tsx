// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { ALL_NAV } from '@/lib/navigation/catalog'

afterEach(() => vi.unstubAllGlobals())

describe('statutory returns are visible from the regulatory catalogue', () => {
  it('lists GSFT and CEÚ, each linking to the statutory-returns workbench', () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('unavailable', { status: 502 })))
    render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)

    for (const id of ['cz-gsft', 'cz-ceu']) {
      const card = document.getElementById(id)
      expect(card, id).not.toBeNull()
      const link = card!.querySelector('a[href="/regulatory/statutory-returns"]')
      expect(link, `${id} workbench link`).not.toBeNull()
    }
    expect(screen.getAllByText(/GSFT/).length).toBeGreaterThan(0)
    expect(screen.getAllByText(/CEÚ/).length).toBeGreaterThan(0)
  })

  it('has a sidebar entry the operator can see', () => {
    const item = ALL_NAV.find(n => n.href === '/regulatory/statutory-returns')
    expect(item?.permission).toBe('statutory-returns:view')
  })
})
