// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cookies } from 'next/headers'
import RootLayout from '@/app/layout'
import { LanguageProvider, useLanguage } from '@/lib/i18n/LanguageContext'
import { LANG_COOKIE, LANG_STORAGE_KEY } from '@/lib/i18n/language'

vi.mock('next/headers', () => ({
  cookies: vi.fn(),
  headers: vi.fn(async () => new Headers()),
}))

function Probe() {
  const { language, setLanguage } = useLanguage()
  return (
    <button type="button" onClick={() => setLanguage(language === 'en' ? 'cs' : 'en')}>
      {language}
    </button>
  )
}

function cookieStore(value?: string) {
  return { get: (name: string) => name === LANG_COOKIE && value ? { value } : undefined }
}

describe('document language', () => {
  beforeEach(() => {
    localStorage.clear()
    document.cookie = `${LANG_COOKIE}=; path=/; max-age=0`
    document.documentElement.lang = 'en'
    vi.mocked(cookies).mockResolvedValue(cookieStore() as never)
  })

  it('server-renders the persisted cookie language on the root element', async () => {
    vi.mocked(cookies).mockResolvedValue(cookieStore('cs') as never)

    const root = await RootLayout({ children: <main /> })

    expect(root.props.lang).toBe('cs')
  })

  it('defaults invalid persisted values to English', async () => {
    vi.mocked(cookies).mockResolvedValue(cookieStore('de') as never)

    const root = await RootLayout({ children: <main /> })

    expect(root.props.lang).toBe('en')
  })

  it('keeps the document, cookie and browser mirror synchronized when toggled', async () => {
    const refreshServerContent = vi.fn()
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<LanguageProvider initialLanguage="cs" refreshServerContent={refreshServerContent}><Probe /></LanguageProvider>)

    await waitFor(() => expect(document.documentElement.lang).toBe('cs'))
    expect(localStorage.getItem(LANG_STORAGE_KEY)).toBe('cs')

    fireEvent.click(screen.getByRole('button', { name: 'cs' }))

    await waitFor(() => expect(document.documentElement.lang).toBe('en'))
    expect(localStorage.getItem(LANG_STORAGE_KEY)).toBe('en')
    expect(document.cookie).toContain(`${LANG_COOKIE}=en`)
    expect(refreshServerContent).toHaveBeenCalledOnce()
  })

  it('migrates a legacy localStorage preference only when no cookie language exists', async () => {
    const refreshServerContent = vi.fn()
    localStorage.setItem(LANG_STORAGE_KEY, 'cs')
    render(<LanguageProvider refreshServerContent={refreshServerContent}><Probe /></LanguageProvider>)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'cs' })).toBeVisible()
      expect(document.documentElement.lang).toBe('cs')
      expect(document.cookie).toContain(`${LANG_COOKIE}=cs`)
    })
    expect(refreshServerContent).toHaveBeenCalledOnce()
  })
})
