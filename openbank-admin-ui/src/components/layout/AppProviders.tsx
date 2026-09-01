// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { usePathname } from 'next/navigation'
import { Toaster } from 'sonner'
import { AgentDock } from '@/components/agent/AgentDock'
import { SessionProvider } from '@/components/auth/SessionProvider'
import { RumScreenTracker } from '@/components/telemetry/RumScreenTracker'
import { isPublicSurface } from '@/lib/auth/publicSurface'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

/**
 * Keeps authenticated-only infrastructure off public entry and policy surfaces.
 * Protected operator routes retain session refresh, expiry recovery and AgentDock.
 */
export function AppProviders({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const publicSurface = isPublicSurface(pathname)
  const shared = (
    <LanguageProvider>
      {children}
      {!publicSurface && <AgentDock />}
      {!publicSurface && <RumScreenTracker />}
      <Toaster richColors position="top-right" />
    </LanguageProvider>
  )

  return publicSurface ? shared : <SessionProvider>{shared}</SessionProvider>
}
