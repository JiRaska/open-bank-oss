// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import type { ReactNode } from 'react'
import { useEffect, useState } from 'react'
import { usePathname } from 'next/navigation'
import { Header } from '@/components/layout/Header'
import { Sidebar } from '@/components/layout/Sidebar'
import { SkipLink } from '@/components/layout/SkipLink'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/**
 * The single authenticated operator shell.
 *
 * Domain layouts used to copy this structure verbatim, which made the visual
 * hierarchy and accessibility behaviour drift as each page family evolved.
 * Keeping the shell here lets domain routes own only their content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const { t } = useLanguage()
  const pathname = usePathname()
  useEffect(() => { setMobileNavOpen(false) }, [pathname])
  useEffect(() => {
    if (!mobileNavOpen) return
    const focusableInSidebar = () => Array.from(
      document.querySelectorAll<HTMLElement>('#admin-sidebar a, #admin-sidebar button:not([disabled])'),
    )
    const frame = requestAnimationFrame(() => {
      focusableInSidebar()[0]?.focus()
    })
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMobileNavOpen(false)
        return
      }
      if (event.key !== 'Tab') return

      const focusable = focusableInSidebar()
      const first = focusable[0]
      const last = focusable.at(-1)
      if (!first || !last) return

      if (!document.querySelector('#admin-sidebar')?.contains(document.activeElement)) {
        event.preventDefault()
        const nextFocus = event.shiftKey ? last : first
        nextFocus.focus()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => { cancelAnimationFrame(frame); window.removeEventListener('keydown', onKeyDown) }
  }, [mobileNavOpen])
  return (
    <div className="ob-app-shell">
      <SkipLink />
      <Sidebar mobileOpen={mobileNavOpen} onClose={() => setMobileNavOpen(false)} />
      {mobileNavOpen && (
        <button
          type="button"
          className="ob-mobile-nav-overlay"
          aria-label={t('Zavřít navigaci', 'Close navigation')}
          onClick={() => setMobileNavOpen(false)}
        />
      )}
      <div className="ob-app-frame">
        <Header mobileNavOpen={mobileNavOpen} onMenuToggle={() => setMobileNavOpen(open => !open)} />
        <main id="main-content" className="ob-app-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  )
}
