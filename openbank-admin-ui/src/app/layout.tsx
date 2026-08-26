// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { Metadata } from 'next'
import './globals.css'
import { headers } from 'next/headers'
import { AppProviders } from '@/components/layout/AppProviders'

export const metadata: Metadata = {
  title: 'OpenBank Admin',
  description: 'OpenBank Operations Portal',
}

// ADR-0080 P1 (nonce CSP fix): reading headers() here forces dynamic (per-request) rendering so
// that Next.js injects the per-request nonce from middleware into the bootstrap <script> tags.
// Without this call Next.js pre-renders the shell statically — the cached <script> tags carry
// no nonce, 'strict-dynamic' blocks them all, and the page renders blank.
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Calling headers() opts the layout out of static pre-rendering (side-effect only).
  await headers()
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
      </head>
      <body>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  )
}
