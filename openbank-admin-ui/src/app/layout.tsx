// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Metadata } from 'next'
import './globals.css'
import { cookies, headers } from 'next/headers'
import { AppProviders } from '@/components/layout/AppProviders'
import { auth } from '@/auth'
import { getRegistry } from '@/lib/services/fleet'
import type { DomainGroup } from '@/lib/governance/domains'
import { currentDomainOwnership } from '@/lib/services/domainOwnership'
import { LANG_COOKIE, parseLanguage } from '@/lib/i18n/language'
import { THEME_COOKIE_KEY, parseTheme } from '@/lib/theme/theme'

export const metadata: Metadata = {
  title: 'OpenBank Admin · Secure operations portal',
  description: 'Sign in to the OpenBank operator workspace with your assigned access.',
  robots: { index: false, follow: false },
}

// The operator console uses the platform font stack. The remote font loader fetches at build time,
// which makes a production artifact depend on public DNS even though runtime CSP forbids it.
// System fonts cover the bilingual UI and keep CI, air-gapped review, and sandbox builds identical.

// ADR-0080 P1 (nonce CSP fix): reading headers() here forces dynamic (per-request) rendering so
// that Next.js injects the per-request nonce from middleware into the bootstrap <script> tags.
// Without this call Next.js pre-renders the shell statically — the cached <script> tags carry
// no nonce, 'strict-dynamic' blocks them all, and the page renders blank.
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Calling headers() opts the layout out of static pre-rendering (side-effect only).
  await headers()
  const requestCookies = await cookies()
  const persistedLanguage = parseLanguage(requestCookies.get(LANG_COOKIE)?.value)
  const initialLanguage = persistedLanguage ?? 'en'
  const initialTheme = parseTheme(requestCookies.get(THEME_COOKIE_KEY)?.value) ?? 'light'
  // Server-side session read (local JWT decode — no network round-trip): seeds the
  // permission-filtered shell so its first paint already carries the operator's roles.
  // Without this the client useSession() resolves a moment later and the whole console
  // nav re-painted on top of the user (measured 0.12+ workspace CLS, see Sidebar).
  const initialSession = await auth()
  const initialRoles: string[] | null = initialSession?.user?.roles ?? null
  const initialOperatorId = initialSession?.user?.id ?? null

  const registry = initialRoles ? getRegistry() : []
  const ownership = initialRoles ? (await currentDomainOwnership(registry)).byModule : new Map<string, DomainGroup>()
  const initialDomainOwnership = Object.fromEntries(registry.map(service =>
    [service.id, ownership.get(service.container) ?? 'unclassified'],
  ))

  return (
    <html lang={initialLanguage} className={initialTheme === 'dark' ? 'dark' : undefined} suppressHydrationWarning>
      <body>
        <AppProviders initialLanguage={persistedLanguage} initialTheme={initialTheme} initialRoles={initialRoles} initialOperatorId={initialOperatorId} initialDomainOwnership={initialDomainOwnership}>{children}</AppProviders>
      </body>
    </html>
  )
}
