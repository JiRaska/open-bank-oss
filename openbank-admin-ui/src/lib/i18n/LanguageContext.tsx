// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

'use client'

import { createContext, useContext, useEffect, useState } from 'react'

type Language = 'en' | 'cs'

interface LanguageContextType {
  language: Language
  setLanguage: (lang: Language) => void
  t: (csText: string, enText: string) => string
}

const LanguageContext = createContext<LanguageContextType | undefined>(undefined)

// Cookie mirror so server components (e.g. /services/[name]/docs page) can
// pick the same language as the client. Cookie expires after a year; same-site
// Lax is sufficient — this is a cosmetic preference, not a security boundary.
export const LANG_COOKIE = 'openbank-admin-lang'

function writeLangCookie(lang: Language) {
  if (typeof document === 'undefined') return
  const maxAge = 60 * 60 * 24 * 365
  document.cookie = `${LANG_COOKIE}=${lang}; path=/; max-age=${maxAge}; SameSite=Lax`
}

export function LanguageProvider({ children }: { children: React.ReactNode }) {
  const [language, setLanguageState] = useState<Language>('en')

  useEffect(() => {
    const saved = localStorage.getItem('openbank-admin-lang') as Language
    if (saved === 'en' || saved === 'cs') {
      setLanguageState(saved)
      writeLangCookie(saved)
    } else {
      // Mirror the default to cookie so the first server-rendered page picks it up.
      writeLangCookie('en')
    }
  }, [])

  const setLanguage = (lang: Language) => {
    setLanguageState(lang)
    localStorage.setItem('openbank-admin-lang', lang)
    writeLangCookie(lang)
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
