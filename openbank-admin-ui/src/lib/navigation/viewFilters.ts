// SPDX-License-Identifier: Apache-2.0
'use client'
import { useEffect, useLayoutEffect, useRef } from 'react'
import { useSearchParams } from 'next/navigation'
export const CAPTURE_VIEW = 'openbank:capture-view'
/**
 * Filter keys a pinned view may persist. A pin lives in localStorage and outlives
 * sign-out, so it must never carry a customer identifier (IBAN, BBAN, account id,
 * counterparty, payment references). Allow-list, not deny-list: a filter added to a
 * page later is dropped from the pin until someone decides it is non-identifying.
 */
export const PERSISTABLE_VIEW_FILTERS: ReadonlySet<string> = new Set(['status', 'type', 'dateFrom', 'dateTo'])
/** True when every query parameter of a pinned href is safe to keep at rest. */
export function isPersistableViewQuery(search: string): boolean {
  for (const key of new URLSearchParams(search).keys()) {
    if (key !== 'view' && !PERSISTABLE_VIEW_FILTERS.has(key)) return false
  }
  return true
}
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
      for (const [key, [value]] of Object.entries(live.current)) if (value && PERSISTABLE_VIEW_FILTERS.has(key)) params.set(key, value)
      if (params.size) params.set('view', 'saved')
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${params.size ? `?${params}` : ''}`)
    }
    window.addEventListener(CAPTURE_VIEW, capture)
    return () => { window.removeEventListener(CAPTURE_VIEW, capture) }
  }, [])
}
