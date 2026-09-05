// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { usePathname } from 'next/navigation'
import { useReportWebVitals } from 'next/web-vitals'
import { useCallback, useEffect, useLayoutEffect, useRef } from 'react'
import { initRum, recordScreenView, recordWebVital } from '@/lib/telemetry/rum'

/** Emits one authenticated App-Router screen-view span per navigation; renders nothing. */
export function RumScreenTracker({ enabled = true }: { enabled?: boolean }) {
  const pathname = usePathname()
  const initialDocumentPath = useRef(pathname)
  const initialDocumentEligible = useRef(enabled)
  const enabledRef = useRef(enabled)

  useLayoutEffect(() => {
    enabledRef.current = enabled
  }, [enabled])

  useEffect(() => {
    if (!enabled) return
    initRum({
      userAgent: window.navigator.userAgent,
      appVersion: process.env.NEXT_PUBLIC_BUILD_VERSION ?? 'dev',
    })
    recordScreenView(pathname)
  }, [enabled, pathname])

  useReportWebVitals(useCallback(metric => {
    if (!initialDocumentEligible.current || !enabledRef.current) return
    recordWebVital(metric, initialDocumentPath.current)
  }, []))

  return null
}
