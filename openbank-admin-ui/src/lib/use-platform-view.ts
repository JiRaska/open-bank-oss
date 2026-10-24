// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import type { PlatformView } from '@/lib/platform-view'

/** Refresh bounded live observations while visible; failed observations are unknown. */
export function usePlatformView(): PlatformView | null {
  const [view, setView] = useState<PlatformView | null>(null)
  useEffect(() => {
    let cancelled = false
    let pending = false
    const refresh = async () => {
      if (pending) return
      pending = true
      try {
        const res = await fetch('/api/platform-versions', { cache: 'no-store', signal: AbortSignal.timeout(10_000) })
        const json = res.ok ? await res.json() as PlatformView : null
        if (!cancelled) setView(json)
      } catch {
        if (!cancelled) setView(null)
      } finally { pending = false }
    }
    void refresh()
    const visible = () => { if (document.visibilityState === 'visible') void refresh() }
    const timer = window.setInterval(visible, 30_000)
    document.addEventListener('visibilitychange', visible)
    return () => { cancelled = true; window.clearInterval(timer); document.removeEventListener('visibilitychange', visible) }
  }, [])
  return view
}
