// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0228 D3: the ⌘K palette. Debounced queries against the entity-resolution facade (D2),
// grouped typed results with keyboard navigation, recent searches per operator in
// sessionStorage. Replaces the painted "Quick search…" placeholder in the header with a real
// one — the audit's showcase example of a feature that looked shipped but was not.

'use client'

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { useSession } from 'next-auth/react'
import { useInitialRoles } from '@/components/auth/InitialRolesContext'
import { hasPermission } from '@/lib/auth/roles'
import { ALL_NAV, currentNavigation, isNavLocked, navigationAreas, searchNavigation } from '@/lib/navigation/catalog'
import { useDomainOwnership } from '@/components/layout/DomainOwnershipContext'
import { useNavigationPreferences } from '@/lib/navigation/preferences'
import { openAssistant } from '@/lib/navigation/assistant'
import { useRouter } from 'next/navigation'
import { Bot, CreditCard, Search, User, X, FileText } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type EntityRef = {
  type: 'party' | 'account' | 'page' | 'pin'
  external?: boolean
  id: string
  label: string
  sublabel?: string
  route: string
}

const RECENTS_KEY = 'ob.palette.recents'
const DEBOUNCE_MS = 300
const MAX_RECENTS = 5

function loadRecents(key: string): EntityRef[] {
  try {
    const data: unknown = JSON.parse(sessionStorage.getItem(key) ?? '[]')
    return Array.isArray(data) ? data.filter(isEntityRef).slice(0, MAX_RECENTS) : []
  } catch {
    return []
  }
}

function pushRecent(ref: EntityRef, key: string) {
  try {
    const rest = loadRecents(key).filter(r => !(r.type === ref.type && r.id === ref.id))
    sessionStorage.setItem(key, JSON.stringify([ref, ...rest].slice(0, MAX_RECENTS)))
  } catch { /* sessionStorage unavailable — recents are a nicety, never a blocker */ }
}

function isEntityRef(value: unknown): value is EntityRef {
  if (!value || typeof value !== 'object') return false
  const ref = value as EntityRef
  if ((ref.type !== 'party' && ref.type !== 'account') || typeof ref.id !== 'string' ||
    !/^[a-zA-Z0-9_-]+$/.test(ref.id) || typeof ref.label !== 'string' || ref.label.length > 200) return false
  return ref.route === `/${ref.type === 'party' ? 'parties' : 'accounts'}/${ref.id}`
}
export function CommandPalette(props: { open: boolean; onClose: () => void }) {
  return props.open ? <OpenCommandPalette onClose={props.onClose} /> : null
}

function OpenCommandPalette({ onClose }: { onClose: () => void }) {
  const open = true
  const router = useRouter()
  const { t } = useLanguage()
  const { data: session } = useSession()
  const initialRoles = useInitialRoles()
  const roles = session?.user?.roles ?? initialRoles ?? []
  const ownership = useDomainOwnership()
  const preferences = useNavigationPreferences()
  const [scope, setScope] = useState<'all' | 'pages' | 'records'>('all')
  const recentsKey = preferences.operatorId ? `${RECENTS_KEY}.${preferences.operatorId}` : ''
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<EntityRef[]>([])
  const [recents] = useState<EntityRef[]>(() => recentsKey ? loadRecents(recentsKey) : [])
  const [active, setActive] = useState(0)
  const [loading, setLoading] = useState(false)
  const [unavailable, setUnavailable] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)

  const areas = navigationAreas(roles, ownership)
  const toPage = (href: string): EntityRef[] => {
    const item = ALL_NAV.find(entry => entry.href === href)
    if (!item || isNavLocked(item, roles) || (item.permission && !hasPermission(roles, item.permission))) return []
    const area = areas.find(entry => entry.items.includes(item))
    return [{ type: 'page', id: item.href, label: t(item.nameCs, item.nameEn), route: item.href,
      external: item.external, sublabel: area ? t(area.nameCs, area.nameEn) : t('Stránka', 'Page') }]
  }
  const pages: EntityRef[] = scope === 'records' ? [] : query.trim()
    ? searchNavigation(query, roles).flatMap(item => toPage(item.href))
    : preferences.recentPages.flatMap(toPage)
  const pins: EntityRef[] = scope === 'records' ? [] : preferences.pins.flatMap(pin => {
    if (!toPage(pin.pathname).length || (query.trim() && !pin.label.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))) return []
    return [{ type: 'pin', id: pin.href, label: pin.label, route: pin.href, external: toPage(pin.pathname)[0]?.external, sublabel: t('Připnutý pohled', 'Pinned view') }]
  })
  const canShowEntity = (ref: EntityRef) => (ref.type === 'party' && hasPermission(roles, 'parties:view')) ||
    (ref.type === 'account' && hasPermission(roles, 'accounts:view'))
  const entities = scope === 'pages' ? [] : (query.trim().length >= 2 ? results : recents).filter(canShowEntity)
  const shown = [...pages, ...pins, ...entities.filter(ref => ref.type === 'party'), ...entities.filter(ref => ref.type === 'account')]
  const canAsk = hasPermission(roles, 'agent:view')
  const askAssistant = (selected?: EntityRef) => {
    const item = currentNavigation(window.location.pathname)
    onClose()
    // Wait until the modal has restored focus before the assistant takes it.
    requestAnimationFrame(() => openAssistant({ prompt: query.trim(), context: {
      label: selected?.label ?? (item ? t(item.nameCs, item.nameEn) : t('Aktuální stránka', 'Current page')),
      route: selected?.route.split('?')[0] ?? window.location.pathname,
    } }))
  }

  useEffect(() => {
    if (!open || query.trim().length < 2 || scope === 'pages') return
    const ctrl = new AbortController()
    const timer = setTimeout(() => {
      fetch(`/api/entities/resolve?q=${encodeURIComponent(query.trim())}`, { signal: ctrl.signal, cache: 'no-store' })
        .then(r => {
          if (!r.ok) throw new Error(`Entity resolution failed with ${r.status}`)
          return r.json()
        })
        .then(d => {
          if (ctrl.signal.aborted) return
          setResults(Array.isArray(d.results) ? d.results.filter(isEntityRef).slice(0, 20) : [])
        })
        .catch(() => {
          if (ctrl.signal.aborted) return
          setResults([])
          setUnavailable(true)
        })
        .finally(() => {
          if (!ctrl.signal.aborted) setLoading(false)
        })
    }, DEBOUNCE_MS)
    return () => { clearTimeout(timer); ctrl.abort() }
  }, [attempt, open, query, scope])

  const choose = (ref: EntityRef) => {
    if (ref.type === 'party' || ref.type === 'account') pushRecent(ref, recentsKey)
    else preferences.recordPage(ref.route.split('?')[0])
    onClose()
    if (ref.external) window.location.assign(ref.route)
    else router.push(ref.route)
  }

  // The keydown listener must never read a STALE `shown`/`active` (#3886).
  //
  // It used to close over both and be re-registered by a `useEffect` keyed on them. A passive
  // effect flushes AFTER the browser has painted, so between React committing a render and that
  // effect running there is a real window in which the DOM already shows N results while the
  // installed listener is still the one captured when `shown` was empty. Under a starved event
  // loop that window widens from microseconds to whole milliseconds — long enough for a keypress
  // to land in it. In that window `Math.min(a + 1, shown.length - 1)` evaluated
  // `Math.min(1, -1) === -1`, so ArrowDown DESELECTED every row, and the following Enter found
  // `shown[-1] === undefined` and navigated nowhere.
  //
  // This is a user-facing defect, not only a test artifact: open ⌘K, type two characters, press ↓
  // during the 300 ms debounce or while the fetch is in flight, then Enter — nothing happens, and
  // nothing ever resets `active` back into range (ArrowUp clamps at 0, the `[open]` effect only
  // runs on open, and the input's onChange needs another keystroke).
  //
  // Fix, both halves:
  //  1. Mirror the live values into refs from a LAYOUT effect. Layout effects flush synchronously
  //     before paint, so "the DOM shows these rows" now implies "the refs describe these rows" —
  //     the window is closed by construction rather than waited out.
  //  2. Clamp ArrowDown at 0. An empty list can no longer produce an out-of-range `active`, so
  //     even a keypress that beats the state entirely leaves a valid selection.
  const shownRef = useRef(shown)
  const activeRef = useRef(active)
  const chooseRef = useRef(choose)
  useLayoutEffect(() => {
    shownRef.current = shown
    activeRef.current = active
    chooseRef.current = choose
  })

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      // Ignore only keys typed into ANOTHER editable control. Focus commonly rests on the
      // dialog container after a mouse click, and arrows/Enter must still drive the list.
      const target = e.target
      const editable = target instanceof HTMLElement && target !== inputRef.current &&
        (target.isContentEditable || target instanceof HTMLInputElement ||
          target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement)
      if (e.isComposing || editable) return
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setActive(a => Math.max(0, Math.min(a + 1, shownRef.current.length - 1)))
      }
      if (e.key === 'ArrowUp') { e.preventDefault(); setActive(a => Math.max(a - 1, 0)) }
      if (e.key === 'Enter') {
        const target = shownRef.current[activeRef.current]
        if (target) { e.preventDefault(); chooseRef.current(target) }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    el?.scrollIntoView?.({ block: 'nearest' })
  }, [active])

  const groups: Array<{ title: string; icon: typeof User; items: Array<{ ref: EntityRef; index: number }> }> = []
  const parties = shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'party')
  const accounts = shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'account')
  if (pages.length) groups.push({ title: query.trim() ? t('Stránky', 'Pages') : t('Nedávno otevřené stránky', 'Recently opened pages'), icon: FileText, items: shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'page') })
  if (pins.length) groups.push({ title: t('Připnuté pohledy', 'Pinned views'), icon: FileText, items: shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'pin') })
  if (parties.length) groups.push({ title: t('Klienti', 'Parties'), icon: User, items: parties })
  if (accounts.length) groups.push({ title: t('Účty', 'Accounts'), icon: CreditCard, items: accounts })

  return (
    <Dialog.Root open={open} onOpenChange={nextOpen => { if (!nextOpen) onClose() }}>
      <Dialog.Portal>
        <Dialog.Overlay style={{ position: 'fixed', inset: 0, zIndex: 100, background: 'rgba(0,0,0,0.45)' }} />
        <Dialog.Content
        onOpenAutoFocus={event => {
          event.preventDefault()
          openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
          inputRef.current?.focus()
        }}
        onCloseAutoFocus={event => {
          event.preventDefault()
          const opener = openerRef.current
          if (opener?.isConnected) opener.focus()
          openerRef.current = null
        }}
        style={{
          position: 'fixed', zIndex: 101, left: '50%', top: '12vh', transform: 'translateX(-50%)',
          width: '680px', maxWidth: '92vw', background: 'var(--surface)',
          border: '1px solid var(--border)', borderRadius: '12px',
          boxShadow: '0 16px 48px rgba(0,0,0,0.35)', overflow: 'hidden',
        }}
      >
        <Dialog.Title style={{ position: 'absolute', width: 1, height: 1, padding: 0, margin: -1, overflow: 'hidden', clip: 'rect(0, 0, 0, 0)', whiteSpace: 'nowrap', border: 0 }}>
          {t('Rychlé hledání', 'Quick search')}
        </Dialog.Title>
        <Dialog.Description style={{ position: 'absolute', width: 1, height: 1, padding: 0, margin: -1, overflow: 'hidden', clip: 'rect(0, 0, 0, 0)', whiteSpace: 'nowrap', border: 0 }}>
          {t('Vyhledejte stránku, klienta nebo účet. Dotaz můžete předat asistentovi.', 'Find a page, party or account. You can hand your query to the assistant.')}
        </Dialog.Description>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
          <Search size={15} aria-hidden="true" style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
          <input
            ref={inputRef}
            value={query}
            onChange={e => {
              const nextQuery = e.target.value
              setQuery(nextQuery)
              setResults([])
              setUnavailable(false)
              setLoading(nextQuery.trim().length >= 2 && scope !== 'pages')
              setActive(0)
            }}
            placeholder={t('Najít stránku, klienta nebo se zeptat…', 'Find a page, customer or ask a question…')}
            aria-label={t('Hledat stránky, klienty a účty', 'Search pages, parties and accounts')}
            aria-controls="command-palette-results"
            aria-autocomplete="list"
            aria-activedescendant={shown[active] ? `command-palette-option-${shown[active].type}-${shown[active].id}` : undefined}
            style={{
              flex: 1, border: 'none', outline: 'none', background: 'transparent',
              fontSize: '14px', color: 'var(--text-primary)',
            }}
          />
          <button type="button" onClick={onClose} aria-label={t('Zavřít', 'Close')} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-tertiary)', padding: 0 }}>
            <X size={15} aria-hidden="true" />
          </button>
        </div>

        <div role="group" aria-label={t('Rozsah hledání', 'Search scope')} style={{ display: 'flex', gap: 6, padding: '10px 16px', borderBottom: '1px solid var(--border)' }}>
          {(['all', 'pages', 'records'] as const).map(value => <button type="button" key={value} aria-pressed={scope === value}
            onClick={() => { setScope(value); setActive(0); setLoading(value !== 'pages' && query.trim().length >= 2); setUnavailable(false); inputRef.current?.focus() }}
            style={{ border: '1px solid var(--border)', borderRadius: 6, padding: '5px 10px', background: scope === value ? 'var(--accent-light)' : 'transparent', color: scope === value ? 'var(--accent-text)' : 'var(--text-secondary)', fontSize: 12, cursor: 'pointer' }}>
            {value === 'all' ? t('Vše', 'All') : value === 'pages' ? t('Stránky', 'Pages') : t('Záznamy', 'Records')}
          </button>)}
        </div>
        <div
          id="command-palette-results"
          ref={listRef}
          role="listbox"
          aria-label={t('Výsledky hledání', 'Search results')}
          aria-busy={loading}
          style={{ maxHeight: '46vh', overflowY: 'auto', padding: '6px' }}
        >
          {query.trim().length < 2 && recents.length > 0 && (
            <div style={{ padding: '6px 10px', fontSize: '10px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)' }}>
              {t('Nedávné', 'Recent')}
            </div>
          )}
          {groups.map(g => (
            <div key={g.title}>
              <div style={{ padding: '6px 10px', fontSize: '10px', fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)' }}>
                {g.title}
              </div>
              {g.items.map(({ ref, index }) => {
                const Icon = g.icon
                const isActive = index === active
                return (
                  <div
                    key={`${ref.type}:${ref.id}`}
                    id={`command-palette-option-${ref.type}-${ref.id}`}
                    onClick={() => choose(ref)}
                    onMouseEnter={() => setActive(index)}
                    role="option" aria-selected={isActive}
                    style={{
                      display: 'flex', alignItems: 'center', gap: '10px', padding: '8px 10px',
                      borderRadius: '8px', cursor: 'pointer',
                      background: isActive ? 'var(--sidebar-active-bg)' : 'transparent',
                    }}
                  >
                    <Icon size={14} style={{ color: 'var(--text-tertiary)', flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {ref.label}
                      </div>
                      {ref.sublabel && (
                        <div style={{ fontSize: '11px', color: 'var(--text-tertiary)' }}>{ref.sublabel}</div>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          ))}
          {query.trim().length >= 2 && scope !== 'pages' && loading && (
            <div role="status" aria-live="polite" style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-tertiary)' }}>
              {t('Hledám klienty a účty…', 'Searching parties and accounts…')}
            </div>
          )}
          {query.trim().length >= 2 && scope !== 'pages' && !loading && unavailable && (
            <div role="alert" style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-secondary)' }}>
              <div>{t('Vyhledávání je dočasně nedostupné.', 'Search is temporarily unavailable.')}</div>
              <button
                type="button"
                onClick={() => {
                  setUnavailable(false)
                  setLoading(true)
                  setAttempt(value => value + 1)
                  requestAnimationFrame(() => inputRef.current?.focus())
                }}
                style={{
                  marginTop: '10px', border: '1px solid var(--border)', borderRadius: '8px',
                  background: 'var(--surface-raised)', color: 'var(--text-primary)',
                  padding: '6px 12px', cursor: 'pointer', fontWeight: 600,
                }}
              >
                {t('Zkusit znovu', 'Try again')}
              </button>
            </div>
          )}
          {query.trim().length >= 1 && !loading && !unavailable && shown.length === 0 && (
            <div style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-tertiary)' }}>
              {t('Nic nenalezeno', 'No results')}
            </div>
          )}
        </div>

        {canAsk && shown[active] && <div style={{ padding: '10px 16px', borderTop: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <span style={{ minWidth: 0, fontSize: 12, color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t('Kontext', 'Context')}: {shown[active].label}</span>
          <button type="button" onClick={() => askAssistant(shown[active])} style={{ flexShrink: 0, background: 'transparent', border: 0, color: 'var(--accent-text)', fontSize: 12, cursor: 'pointer' }}>{t('Zeptat se k výsledku', 'Ask about result')}</button>
        </div>}
        {canAsk && query.trim() && <button type="button" onClick={() => askAssistant()}
          style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '14px 16px', border: 0, borderTop: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--accent-text)', textAlign: 'left', cursor: 'pointer' }}>
          <Bot size={16} aria-hidden="true" /><span>{t('Zeptat se asistenta', 'Ask the assistant')} <small>· {t('s kontextem aktuální stránky', 'with current page context')}</small></span>
        </button>}
        <div style={{ padding: '8px 16px', borderTop: '1px solid var(--border)', display: 'flex', gap: '12px', fontSize: '10px', color: 'var(--text-tertiary)' }}>
          <span>↑↓ {t('výběr', 'select')}</span>
          <span>↵ {t('otevřít', 'open')}</span>
          <span>esc {t('zavřít', 'close')}</span>
        </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
