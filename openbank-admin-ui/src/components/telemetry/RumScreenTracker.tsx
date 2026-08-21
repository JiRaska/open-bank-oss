// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { usePathname } from 'next/navigation'
import { useEffect } from 'react'
import { initRum, recordScreenView } from '@/lib/telemetry/rum'

/**
 * Emits one RUM screen-view span per App-Router navigation (issue #5735). Renders nothing.
 *
 * `NEXT_PUBLIC_BUILD_VERSION` is baked into the bundle at build time — which is CORRECT for
 * this one value, because `app.version` must identify the bundle the browser is running, not
 * whatever the pod's env says today. The collector endpoint, which does have to vary per
 * environment, is a server-side runtime read in the relay route instead.
 */
export function RumScreenTracker() {
  const pathname = usePathname()

  useEffect(() => {
    if (typeof window === 'undefined') return
    initRum({
      userAgent: window.navigator.userAgent,
      appVersion: process.env.NEXT_PUBLIC_BUILD_VERSION ?? 'dev',
    })
    recordScreenView(pathname)
  }, [pathname])

  return null
}
