// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { THEME_COOKIE_KEY, THEME_STORAGE_KEY, parseTheme, type Theme } from './theme'

export { THEME_COOKIE_KEY, THEME_STORAGE_KEY, type Theme } from './theme'

/**
 * Why this file exists (#9831).
 *
 * `globals.css` carries a 67-token `.dark` block, four merged PRs themed consoles for it, and
 * `core-workflow-dark-accessibility.spec.ts` scans it on every PR — while NOTHING in the shipped
 * app ever applied the class. Measured: no `classList.add('dark')` and no `data-theme` anywhere
 * under `src/` or `public/` (the same pattern finds five writers under `e2e/`, so the probe
 * works), and `prefers-color-scheme` appears zero times in `globals.css`. The only two readers,
 * `ContextualInsights` and `WarehouseDashboard`, were therefore permanently false in production.
 *
 * So the theme was reachable only from Playwright, which adds the class itself — a gate green
 * about a state no user could see.
 *
 * DEFAULT IS LIGHT, DELIBERATELY, and this is the one judgement here worth stating. #9831 proposes
 * `prefers-color-scheme` as the default, which is defensible — but it would change the appearance
 * of every operator's console on the next deploy without anyone choosing it. Making the theme
 * REACHABLE is what was missing; making it AUTOMATIC is a separate, visible product decision.
 * The hook for it is one line in [initialTheme] and is left to the owner.
 */
function readStoredTheme(): Theme | null {
  // localStorage throws in a private window, with site data blocked, and during thumbnail
  // capture — a theme preference is not worth a blank console, so every access is guarded.
  try {
    return parseTheme(window.localStorage.getItem(THEME_STORAGE_KEY))
  } catch {
    return null
  }
}

/** The theme to start from when the operator has expressed no choice. See the note above. */
export function initialTheme(): Theme {
  return readCookieTheme()
    ?? readStoredTheme()
    ?? (document.documentElement.classList.contains('dark') ? 'dark' : 'light')
}

function readCookieTheme(): Theme | null {
  const cookie = document.cookie
    .split(';')
    .map(part => part.trim())
    .find(part => part.startsWith(`${THEME_COOKIE_KEY}=`))
    ?.slice(THEME_COOKIE_KEY.length + 1)
  return parseTheme(cookie)
}

export function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

function persistTheme(theme: Theme): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // The cookie still preserves the choice when storage is blocked.
  }
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${THEME_COOKIE_KEY}=${theme}; Path=/; Max-Age=31536000; SameSite=Lax${secure}`
}

type ThemeContextValue = { theme: Theme; setTheme: (next: Theme) => void; toggle: () => void }

const ThemeContext = createContext<ThemeContextValue | null>(null)

export function ThemeProvider({ children, initialTheme: serverTheme }: { children: ReactNode; initialTheme: Theme }) {
  // The server read the same cookie that produced the <html class="dark"> marker, so CSS and
  // control state agree on the first paint and hydration never needs a corrective render.
  const [theme, setThemeState] = useState<Theme>(serverTheme)

  useEffect(() => {
    // One-time compatibility bridge for preferences saved before the server-readable cookie
    // existed. New and migrated sessions never take this path, so normal hydration remains a
    // single render with no theme flash.
    if (readCookieTheme()) return
    const legacyTheme = readStoredTheme()
    if (!legacyTheme || legacyTheme === serverTheme) return
    persistTheme(legacyTheme)
    // eslint-disable-next-line react-hooks/set-state-in-effect -- bounded legacy migration
    setThemeState(legacyTheme)
  }, [serverTheme])

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  const setTheme = useCallback((next: Theme) => {
    setThemeState(next)
    persistTheme(next)
  }, [])

  const toggle = useCallback(() => {
    setThemeState(current => {
      const next: Theme = current === 'dark' ? 'light' : 'dark'
      persistTheme(next)
      return next
    })
  }, [])

  const value = useMemo(() => ({ theme, setTheme, toggle }), [setTheme, theme, toggle])
  return createElement(ThemeContext.Provider, { value }, children)
}

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext)
  if (!value) throw new Error('useTheme must be used within ThemeProvider')
  return value
}
