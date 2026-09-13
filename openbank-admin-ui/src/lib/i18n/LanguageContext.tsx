// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { createContext, useContext, useEffect, useState } from 'react'
import { LANG_COOKIE, LANG_STORAGE_KEY, parseLanguage, type Language } from './language'

export { LANG_COOKIE } from './language'
export type { Language } from './language'

interface LanguageContextType {
  language: Language
  setLanguage: (lang: Language) => void
  t: (csText: string, enText: string) => string
}

const LanguageContext = createContext<LanguageContextType | undefined>(undefined)

// The cookie is the shared server/client preference (server components cannot
// read localStorage). It expires after a year; SameSite=Lax is sufficient —
// this is a cosmetic preference, not a security boundary.
function writeLangCookie(lang: Language) {
  if (typeof document === 'undefined') return
  const maxAge = 60 * 60 * 24 * 365
  document.cookie = `${LANG_COOKIE}=${lang}; path=/; max-age=${maxAge}; SameSite=Lax`
}

export function LanguageProvider({
  children,
  initialLanguage = null,
  refreshServerContent,
}: {
  children: React.ReactNode
  initialLanguage?: Language | null
  refreshServerContent?: () => void
}) {
  const [language, setLanguageState] = useState<Language>(initialLanguage ?? 'en')

  useEffect(() => {
    if (initialLanguage) {
      // The cookie is visible to both server and client, so it is authoritative
      // when present. Keep the legacy localStorage mirror in sync instead of
      // letting a stale browser value replace server-rendered content.
      localStorage.setItem(LANG_STORAGE_KEY, initialLanguage)
      return
    }

    // Migrate browsers that saved the preference before the cookie mirror was
    // introduced. The first render remains English on both server and client;
    // the legacy preference is applied only after hydration.
    const saved = parseLanguage(localStorage.getItem(LANG_STORAGE_KEY))
    if (saved) {
      const migration = window.setTimeout(() => {
        document.documentElement.lang = saved
        setLanguageState(saved)
        writeLangCookie(saved)
        refreshServerContent?.()
      }, 0)
      return () => window.clearTimeout(migration)
    } else {
      // Mirror the default to cookie so the first server-rendered page picks it up.
      writeLangCookie('en')
    }
  }, [initialLanguage, refreshServerContent])

  useEffect(() => {
    document.documentElement.lang = language
  }, [language])

  const setLanguage = (lang: Language) => {
    // Keep the document truthful in the same interaction; the effect below also
    // covers initial hydration and non-interactive state changes.
    document.documentElement.lang = lang
    setLanguageState(lang)
    localStorage.setItem(LANG_STORAGE_KEY, lang)
    writeLangCookie(lang)
    // Several documentation pages localize in Server Components by reading the
    // cookie. Refresh their RSC payload after the synchronous cookie write so
    // visible copy and the root language never disagree until navigation.
    refreshServerContent?.()
  }

  const t = (csText: string, enText: string) => {
    return language === 'cs' ? csText : enText
  }

  return (
    <LanguageContext.Provider value={{ language, setLanguage, t }}>
      {children}
    </LanguageContext.Provider>
  )
}

export function useLanguage() {
  const context = useContext(LanguageContext)
  if (!context) {
    throw new Error('useLanguage must be used within a LanguageProvider')
  }
  return context
}
