// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'
import { Header } from '@/components/layout/Header'
import { Sidebar } from '@/components/layout/Sidebar'

/**
 * The single authenticated operator shell.
 *
 * Domain layouts used to copy this structure verbatim, which made the visual
 * hierarchy and accessibility behaviour drift as each page family evolved.
 * Keeping the shell here lets domain routes own only their content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="ob-app-shell">
      <a className="ob-skip-link" href="#main-content">Skip to main content</a>
      <Sidebar />
      <div className="ob-app-frame">
        <Header />
        <main id="main-content" className="ob-app-content" tabIndex={-1}>{children}</main>
      </div>
    </div>
  )
}
