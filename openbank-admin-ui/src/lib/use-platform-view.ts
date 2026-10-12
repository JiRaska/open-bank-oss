// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

'use client'

import { useEffect, useState } from 'react'
import type { PlatformView } from '@/lib/platform-view'

/** Fetches /api/platform-versions once; null until loaded or when the endpoint is unavailable. */
export function usePlatformView(): PlatformView | null {
  const [view, setView] = useState<PlatformView | null>(null)
  useEffect(() => {
    let cancelled = false
    fetch('/api/platform-versions', { cache: 'no-store' })
      .then(res => (res.ok ? res.json() : null))
      .then((json: PlatformView | null) => { if (!cancelled) setView(json) })
      .catch(() => { if (!cancelled) setView(null) })
    return () => { cancelled = true }
  }, [])
  return view
}
