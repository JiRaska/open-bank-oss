// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { usePathname } from 'next/navigation'
import { useEffect } from 'react'
import { initRum, recordScreenView } from '@/lib/telemetry/rum'

/** Emits one authenticated App-Router screen-view span per navigation; renders nothing. */
export function RumScreenTracker() {
  const pathname = usePathname()

  useEffect(() => {
    initRum({
      userAgent: window.navigator.userAgent,
      appVersion: process.env.NEXT_PUBLIC_BUILD_VERSION ?? 'dev',
    })
    recordScreenView(pathname)
  }, [pathname])

  return null
}
