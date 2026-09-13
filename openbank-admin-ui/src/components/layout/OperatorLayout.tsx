// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { ReactNode } from 'react'
import { AppShell } from './AppShell'

/**
 * Default Next.js layout for an authenticated operator route.
 *
 * Route folders still declare a `layout.tsx`: that is how the App Router makes
 * the shell available to independent route trees. They must re-export this
 * component rather than copy its implementation, so navigation, landmarks and
 * future shell changes have one owner (ADR-0208 D3).
 */
export default function OperatorLayout({ children }: { children: ReactNode }) {
  return <AppShell>{children}</AppShell>
}
