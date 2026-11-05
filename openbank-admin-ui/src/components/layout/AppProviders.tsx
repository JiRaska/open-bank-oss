// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useCallback } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { Toaster } from 'sonner'
import { AgentDock } from '@/components/agent/AgentDock'
import { SessionProvider } from '@/components/auth/SessionProvider'
import { InitialRolesProvider } from '@/components/auth/InitialRolesContext'
import { RumScreenTracker } from '@/components/telemetry/RumScreenTracker'
import { isPublicSurface } from '@/lib/auth/publicSurface'
import { LanguageProvider, type Language } from '@/lib/i18n/LanguageContext'
import { ThemeProvider } from '@/lib/theme/useTheme'
import type { Theme } from '@/lib/theme/theme'

/**
 * Keeps authenticated-only infrastructure off public entry and policy surfaces.
 * Protected operator routes retain session refresh, expiry recovery and AgentDock.
 */
export function AppProviders({
  children,
  initialLanguage = null,
  initialTheme = 'light',
  initialRoles = null,
}: {
  children: React.ReactNode
  initialLanguage?: Language | null
  initialTheme?: Theme
  initialRoles?: string[] | null
}) {
  const pathname = usePathname()
  const router = useRouter()
  const refreshServerContent = useCallback(() => router.refresh(), [router])
  const publicSurface = isPublicSurface(pathname)
  const shared = (
    <ThemeProvider initialTheme={initialTheme}>
      <LanguageProvider initialLanguage={initialLanguage} refreshServerContent={refreshServerContent}>
        <InitialRolesProvider roles={initialRoles}>
          {children}
          {!publicSurface && <AgentDock />}
          <Toaster richColors position="top-right" />
        </InitialRolesProvider>
      </LanguageProvider>
    </ThemeProvider>
  )

  return (
    <>
      <RumScreenTracker enabled={!publicSurface} />
      {publicSurface ? shared : <SessionProvider>{shared}</SessionProvider>}
    </>
  )
}
