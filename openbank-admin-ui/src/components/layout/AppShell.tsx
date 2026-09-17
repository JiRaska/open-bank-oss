// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import type { ReactNode } from 'react'
import { useEffect, useState } from 'react'
import { usePathname } from 'next/navigation'
import { useSession } from 'next-auth/react'
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
  const { status: sessionStatus } = useSession()
  const { t } = useLanguage()
  const pathname = usePathname()
  useEffect(() => { setMobileNavOpen(false) }, [pathname])
  useEffect(() => {
    if (!mobileNavOpen) return
    const focusableInSidebar = () => Array.from(
      document.querySelectorAll<HTMLElement>('#admin-sidebar a, #admin-sidebar button:not([disabled])'),
    )
    // The mobile drawer starts with visibility:hidden. Its open class is committed
    // in this render, but focusing in the same effect can run before the browser
    // applies the visible style and silently leave focus on the opener.
    let focusFrame = 0
    let focusAttempts = 0
    const focusWhenVisible = () => {
      const sidebar = document.querySelector<HTMLElement>('#admin-sidebar')
      const opener = document.querySelector<HTMLElement>('button[aria-controls="admin-sidebar"]')
      if (document.activeElement !== opener && document.activeElement !== document.body) return
      if (sidebar && getComputedStyle(sidebar).visibility === 'visible') sidebar.focus()
      if (document.activeElement !== sidebar && ++focusAttempts < 15) {
        focusFrame = requestAnimationFrame(focusWhenVisible)
      }
    }
    focusFrame = requestAnimationFrame(focusWhenVisible)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMobileNavOpen(false)
        return
      }
      if (event.key !== 'Tab') return

      const sidebar = document.querySelector<HTMLElement>('#admin-sidebar')
      const focusable = focusableInSidebar()
      const first = focusable[0]
      const last = focusable.at(-1)
      if (!first || !last) return

      if (document.activeElement === sidebar || !sidebar?.contains(document.activeElement)) {
        event.preventDefault()
        const nextFocus = event.shiftKey ? last : first
        const originalFocus = document.activeElement
        let attempts = 0
        const focusNext = () => {
          if (document.activeElement !== originalFocus || !sidebar?.contains(nextFocus)) return
          nextFocus.focus()
          if (document.activeElement !== nextFocus && ++attempts < 15) {
            focusFrame = requestAnimationFrame(focusNext)
          }
        }
        focusNext()
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => { cancelAnimationFrame(focusFrame); window.removeEventListener('keydown', onKeyDown) }
  }, [mobileNavOpen])
  useEffect(() => {
    if (!mobileNavOpen || sessionStatus === 'loading') return
    const frame = requestAnimationFrame(() => {
      const sidebar = document.querySelector<HTMLElement>('#admin-sidebar')
      const opener = document.querySelector<HTMLElement>('button[aria-controls="admin-sidebar"]')
      if (document.activeElement !== sidebar && document.activeElement !== opener) return
      sidebar?.querySelector<HTMLElement>('a, button:not([disabled])')?.focus()
    })
    return () => { cancelAnimationFrame(frame) }
  }, [mobileNavOpen, sessionStatus])
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
