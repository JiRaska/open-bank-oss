// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Metadata } from 'next'
import './globals.css'
import { headers } from 'next/headers'
import { Plus_Jakarta_Sans, JetBrains_Mono } from 'next/font/google'
import { AppProviders } from '@/components/layout/AppProviders'

export const metadata: Metadata = {
  title: 'OpenBank Admin',
  description: 'OpenBank Operations Portal',
}

// Self-hosted via next/font (build-time fetch, cached under .next/static — no browser request
// to any third-party font origin at runtime). 'latin-ext' covers the Czech diacritics the
// bilingual UI renders. Weight/style set matches the CSS @import this replaces.
const plusJakartaSans = Plus_Jakarta_Sans({
  subsets: ['latin', 'latin-ext'],
  weight: ['400', '500', '600', '700', '800'],
  style: ['normal', 'italic'],
  display: 'swap',
  variable: '--font-plus-jakarta-sans',
})
const jetbrainsMono = JetBrains_Mono({
  subsets: ['latin', 'latin-ext'],
  weight: ['400', '500'],
  display: 'swap',
  variable: '--font-jetbrains-mono',
})

// ADR-0080 P1 (nonce CSP fix): reading headers() here forces dynamic (per-request) rendering so
// that Next.js injects the per-request nonce from middleware into the bootstrap <script> tags.
// Without this call Next.js pre-renders the shell statically — the cached <script> tags carry
// no nonce, 'strict-dynamic' blocks them all, and the page renders blank.
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Calling headers() opts the layout out of static pre-rendering (side-effect only).
  await headers()
  return (
    <html lang="en" suppressHydrationWarning className={`${plusJakartaSans.variable} ${jetbrainsMono.variable}`}>
      <body>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  )
}
