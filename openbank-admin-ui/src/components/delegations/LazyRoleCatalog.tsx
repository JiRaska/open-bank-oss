// SPDX-License-Identifier: Apache-2.0

'use client'

import dynamic from 'next/dynamic'
import { useEffect, useRef, useState } from 'react'

const RoleCatalog = dynamic(
  () => import('./RoleCatalog').then(module => module.RoleCatalog),
  { loading: () => <div style={{ minHeight: 240 }} aria-hidden="true" /> },
)

/** Defer the independent preset-management workflow until it approaches the viewport or idle warm-up. */
export function LazyRoleCatalog() {
  const boundary = useRef<HTMLDivElement>(null)
  const [nearViewport, setNearViewport] = useState(false)

  useEffect(() => {
    const element = boundary.current
    if (!element) return
    if (typeof IntersectionObserver === 'undefined') {
      const fallback = setTimeout(() => setNearViewport(true), 0)
      return () => clearTimeout(fallback)
    }
    let warmup: ReturnType<typeof setTimeout>
    const observer = new IntersectionObserver(entries => {
      if (!entries.some(entry => entry.isIntersecting)) return
      clearTimeout(warmup)
      setNearViewport(true)
      observer.disconnect()
    }, { rootMargin: '600px 0px' })
    // Keep direct catalog workflows discoverable without requiring a scroll, but let the primary
    // party lookup hydrate first instead of competing for its initial network and parse budget.
    warmup = setTimeout(() => {
      setNearViewport(true)
      observer.disconnect()
    }, 1_500)
    observer.observe(element)
    return () => {
      clearTimeout(warmup)
      observer.disconnect()
    }
  }, [])

  return (
    <div ref={boundary} style={{ minHeight: nearViewport ? undefined : 240 }}>
      {nearViewport ? <RoleCatalog /> : null}
    </div>
  )
}
