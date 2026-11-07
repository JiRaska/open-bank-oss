// SPDX-License-Identifier: Apache-2.0
'use client'

import { useCallback, useSyncExternalStore } from 'react'
import { useInitialOperatorId } from '@/components/auth/InitialRolesContext'
import { useSession } from 'next-auth/react'
import { ALL_NAV } from './catalog'

export type PinnedView = { href: string; pathname: string; label: string }
type Preferences = { area: string; compact: boolean; width: number; pins: PinnedView[]; recentPages: string[] }
const DEFAULTS: Preferences = { area: '', compact: false, width: 280, pins: [], recentPages: [] }
const cache = new Map<string, { raw: string | null; value: Preferences; memoryOnly?: boolean }>()
const EVENT = 'openbank:navigation-preferences'

export function parsePreferences(raw: string | null): Preferences {
  try {
    const data = JSON.parse(raw ?? '{}')
    const pins = Array.isArray(data.pins) ? data.pins.filter((pin: PinnedView) =>
      pin && typeof pin.href === 'string' && pin.href.length <= 4096 &&
      typeof pin.label === 'string' && pin.label.length <= 100 &&
      ALL_NAV.some(item => item.href === pin.pathname) &&
      (pin.href === pin.pathname || pin.href.startsWith(`${pin.pathname}?`)),
    ).slice(0, 12) : []
    return {
      area: typeof data.area === 'string' ? data.area : '', compact: data.compact === true,
      width: Number.isFinite(data.width) ? Math.max(248, Math.min(360, data.width)) : 280,
      pins, recentPages: Array.isArray(data.recentPages)
        ? data.recentPages.filter((href: unknown) => ALL_NAV.some(item => item.href === href)).slice(0, 6) : [],
    }
  } catch { return DEFAULTS }
}
function read(key: string): Preferences {
  if (!key) return DEFAULTS
  if (cache.get(key)?.memoryOnly) return cache.get(key)!.value
  let raw: string | null = null
  try { raw = localStorage.getItem(key) } catch { return cache.get(key)?.value ?? DEFAULTS }
  const existing = cache.get(key)
  if (existing?.raw === raw) return existing.value
  const value = parsePreferences(raw)
  cache.set(key, { raw, value })
  return value
}
function subscribe(callback: () => void) {
  window.addEventListener(EVENT, callback)
  window.addEventListener('storage', callback)
  return () => { window.removeEventListener(EVENT, callback); window.removeEventListener('storage', callback) }
}
export function useNavigationPreferences() {
  const { data: session, status } = useSession()
  const initialOperatorId = useInitialOperatorId()
  const operatorId = session?.user?.id ?? (status === 'loading' ? initialOperatorId : null)
  const key = operatorId ? `ob.navigation.v1.${operatorId}` : ''
  const getSnapshot = useCallback(() => read(key), [key])
  const value = useSyncExternalStore(subscribe, getSnapshot, () => DEFAULTS)
  const update = useCallback((change: (current: Preferences) => Preferences) => {
    if (!key) return
    const next = change(read(key))
    const raw = JSON.stringify(next)
    cache.set(key, { raw, value: next })
    try { localStorage.setItem(key, raw) } catch { cache.set(key, { raw, value: next, memoryOnly: true }) }
    window.dispatchEvent(new Event(EVENT))
  }, [key])
  return {
    ...value, operatorId,
    selectArea: (area: string) => update(current => current.area === area ? current : { ...current, area }),
    toggleCompact: () => update(current => ({ ...current, compact: !current.compact })),
    setWidth: (width: number) => update(current => ({ ...current, width })),
    togglePin: (pin: PinnedView) => update(current => ({ ...current, pins: current.pins.some(item => item.href === pin.href)
      ? current.pins.filter(item => item.href !== pin.href) : current.pins.length < 12 ? [...current.pins, pin] : current.pins })),
    recordPage: (href: string) => update(current => current.recentPages[0] === href ? current : {
      ...current, recentPages: [href, ...current.recentPages.filter(item => item !== href)].slice(0, 6),
    }),
  }
}
