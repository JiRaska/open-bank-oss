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
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      setMobileNavOpen(false)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => { window.removeEventListener('keydown', closeOnEscape) }
  }, [mobileNavOpen])
  useEffect(() => {
    if (!mobileNavOpen || sessionStatus === 'loading') return
    const frame = requestAnimationFrame(() => {
      const sidebar = Array.from(document.querySelectorAll<HTMLElement>('#admin-sidebar'))
        .find(element => element.checkVisibility())
      const opener = Array.from(document.querySelectorAll<HTMLElement>('button[aria-controls="admin-sidebar"]'))
        .find(element => element.checkVisibility())
      if (document.activeElement !== sidebar && document.activeElement !== opener) return
      sidebar?.querySelector<HTMLElement>('a, button:not([disabled])')?.focus()
    })
    return () => { cancelAnimationFrame(frame) }
  }, [mobileNavOpen, sessionStatus])
  const toggleMobileNav = () => { setMobileNavOpen(open => !open) }
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
        <Header mobileNavOpen={mobileNavOpen} onMenuToggle={toggleMobileNav} />
        <main id="main-content" className="ob-app-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  )
}
