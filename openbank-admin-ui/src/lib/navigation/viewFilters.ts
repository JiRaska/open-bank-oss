// SPDX-License-Identifier: Apache-2.0
'use client'
import { useEffect, useLayoutEffect, useRef } from 'react'
import { useSearchParams } from 'next/navigation'
export const CAPTURE_VIEW = 'openbank:capture-view'
/** Capture only page-owned filters when the operator explicitly pins a view. */
export function useViewFilters(fields: Record<string, [string, (value: string) => void]>) {
  const params = useSearchParams()
  const search = params?.toString() ?? ''
  const live = useRef(fields)
  const wasSaved = useRef(false)
  useLayoutEffect(() => { live.current = fields })
  useEffect(() => {
    const saved = new URLSearchParams(search)
    const isSaved = saved.get('view') === 'saved'
    if (!isSaved && !wasSaved.current) return
    wasSaved.current = isSaved
    for (const [key, [, setter]] of Object.entries(live.current)) setter(isSaved ? saved.get(key)?.slice(0, 256) ?? '' : '')
  }, [search])
  useEffect(() => {
    const capture = () => {
      const params = new URLSearchParams()
      for (const [key, [value]] of Object.entries(live.current)) if (value) params.set(key, value)
      if (params.size) params.set('view', 'saved')
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${params.size ? `?${params}` : ''}`)
    }
    window.addEventListener(CAPTURE_VIEW, capture)
    return () => { window.removeEventListener(CAPTURE_VIEW, capture) }
  }, [])
}
