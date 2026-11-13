// SPDX-License-Identifier: Apache-2.0
'use client'
import { useEffect, useLayoutEffect, useRef } from 'react'
import { useSearchParams } from 'next/navigation'
export const CAPTURE_VIEW = 'openbank:capture-view'
/**
 * Filters a pinned view may persist, each with the only value SHAPE it may carry. A pin lives
 * in localStorage and outlives sign-out, so it must never hold a customer identifier (IBAN,
 * BBAN, account id, counterparty, payment references). Keys AND values are checked: a key
 * check alone lets `status=<IBAN>` through, because the restore path copies any URL string
 * into page state. Allow-list, not deny-list: a filter added later is dropped from the pin
 * until someone decides it is non-identifying and gives it a shape. Enum shapes admit no
 * digits, which is what keeps an IBAN or account number out of them. Amounts are left out on
 * purpose: a bare digit string is indistinguishable from an account number.
 */
const PERSISTABLE_VIEW_FILTERS: ReadonlyMap<string, RegExp> = new Map([
  ['status', /^[A-Z_]{1,32}$/],
  ['type', /^[A-Z_]{1,32}$/],
  ['tab', /^[a-z][a-z-]{0,31}$/],
  ['dateFrom', /^\d{4}-\d{2}-\d{2}$/],
  ['dateTo', /^\d{4}-\d{2}-\d{2}$/],
])
export function isPersistableViewFilter(key: string, value: string): boolean {
  if (key === 'view') return value === 'saved'
  return PERSISTABLE_VIEW_FILTERS.get(key)?.test(value) ?? false
}
/** True when every query parameter of a pinned href's query (text after `?`) is safe at rest. */
export function isPersistableViewQuery(search: string): boolean {
  if (search.includes('#') || search.includes('?')) return false
  for (const [key, value] of new URLSearchParams(search)) if (!isPersistableViewFilter(key, value)) return false
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
      for (const [key, [value]] of Object.entries(live.current)) if (value && isPersistableViewFilter(key, value)) params.set(key, value)
      if (params.size) params.set('view', 'saved')
      window.history.replaceState(window.history.state, '', `${window.location.pathname}${params.size ? `?${params}` : ''}`)
    }
    window.addEventListener(CAPTURE_VIEW, capture)
    return () => { window.removeEventListener(CAPTURE_VIEW, capture) }
  }, [])
}
