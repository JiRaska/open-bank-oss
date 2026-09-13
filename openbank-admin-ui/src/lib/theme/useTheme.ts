// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback, useEffect, useState } from 'react'

export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'ob-admin-theme'

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
    const raw = window.localStorage.getItem(THEME_STORAGE_KEY)
    return raw === 'dark' || raw === 'light' ? raw : null
  } catch {
    return null
  }
}

/** The theme to start from when the operator has expressed no choice. See the note above. */
export function initialTheme(): Theme {
  return readStoredTheme() ?? 'light'
}

export function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

export function useTheme(): { theme: Theme; setTheme: (next: Theme) => void; toggle: () => void } {
  // Starts light on the server AND on the first client render: reading localStorage during render
  // would make the two disagree, which React resolves by discarding the server HTML. The stored
  // choice is applied in the effect below, one frame later.
  const [theme, setThemeState] = useState<Theme>('light')

  useEffect(() => {
    const stored = initialTheme()
    setThemeState(stored)
    applyTheme(stored)
  }, [])

  const setTheme = useCallback((next: Theme) => {
    setThemeState(next)
    applyTheme(next)
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, next)
    } catch {
      // The choice still applies to this session; it simply will not survive a reload.
    }
  }, [])

  const toggle = useCallback(() => {
    setThemeState(current => {
      const next: Theme = current === 'dark' ? 'light' : 'dark'
      applyTheme(next)
      try {
        window.localStorage.setItem(THEME_STORAGE_KEY, next)
      } catch {
        // as above
      }
      return next
    })
  }, [])

  return { theme, setTheme, toggle }
}
