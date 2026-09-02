// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0228 D3: the ⌘K palette. Debounced queries against the entity-resolution facade (D2),
// grouped typed results with keyboard navigation, recent searches per operator in
// sessionStorage. Replaces the painted "Quick search…" placeholder in the header with a real
// one — the audit's showcase example of a feature that looked shipped but was not.

'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { CreditCard, Search, User, X } from 'lucide-react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

type EntityRef = {
  type: 'party' | 'account'
  id: string
  label: string
  sublabel?: string
  route: string
}

const RECENTS_KEY = 'ob.palette.recents'
const DEBOUNCE_MS = 300
const MAX_RECENTS = 5

function loadRecents(): EntityRef[] {
  try {
    return JSON.parse(sessionStorage.getItem(RECENTS_KEY) ?? '[]') as EntityRef[]
  } catch {
    return []
  }
}

function pushRecent(ref: EntityRef) {
  try {
    const rest = loadRecents().filter(r => !(r.type === ref.type && r.id === ref.id))
    sessionStorage.setItem(RECENTS_KEY, JSON.stringify([ref, ...rest].slice(0, MAX_RECENTS)))
  } catch { /* sessionStorage unavailable — recents are a nicety, never a blocker */ }
}

export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const router = useRouter()
  const { t } = useLanguage()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<EntityRef[]>([])
  const [recents, setRecents] = useState<EntityRef[]>([])
  const [active, setActive] = useState(0)
  const [loading, setLoading] = useState(false)
  const [unavailable, setUnavailable] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)

  const shown = query.trim().length >= 2 ? results : recents

  useEffect(() => {
    if (open) {
      openerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      setQuery('')
      setResults([])
      setUnavailable(false)
      setLoading(false)
      setRecents(loadRecents())
      setActive(0)
      setTimeout(() => inputRef.current?.focus(), 0)
    } else if (openerRef.current) {
      openerRef.current.focus()
      openerRef.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open || query.trim().length < 2) return
    const ctrl = new AbortController()
    const timer = setTimeout(() => {
      fetch(`/api/entities/resolve?q=${encodeURIComponent(query.trim())}`, { signal: ctrl.signal, cache: 'no-store' })
        .then(r => {
          if (!r.ok) throw new Error(`Entity resolution failed with ${r.status}`)
          return r.json()
        })
        .then(d => {
          if (ctrl.signal.aborted) return
          setResults(d.results ?? [])
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
  }, [attempt, open, query])

  const choose = useCallback((ref: EntityRef) => {
    pushRecent(ref)
    onClose()
    router.push(ref.route)
  }, [onClose, router])

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
  const onCloseRef = useRef(onClose)
  useLayoutEffect(() => {
    shownRef.current = shown
    activeRef.current = active
    chooseRef.current = choose
    onCloseRef.current = onClose
  })

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onCloseRef.current() }
      if (e.key === 'Tab') {
        const focusables = Array.from(dialogRef.current?.querySelectorAll<HTMLElement>(
          'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
        ) ?? [])
        if (focusables.length === 0) return
        const first = focusables[0]
        const last = focusables[focusables.length - 1]
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
      }
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
    const el = listRef.current?.children[active] as HTMLElement | undefined
    el?.scrollIntoView?.({ block: 'nearest' })
  }, [active])

  if (!open) return null

  const groups: Array<{ title: string; icon: typeof User; items: Array<{ ref: EntityRef; index: number }> }> = []
  const parties = shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'party')
  const accounts = shown.map((ref, index) => ({ ref, index })).filter(x => x.ref.type === 'account')
  if (parties.length) groups.push({ title: t('Klienti', 'Parties'), icon: User, items: parties })
  if (accounts.length) groups.push({ title: t('Účty', 'Accounts'), icon: CreditCard, items: accounts })

  return (
    <div
      role="dialog" aria-modal="true" aria-label={t('Rychlé hledání', 'Quick search')}
      ref={dialogRef}
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 100, background: 'rgba(0,0,0,0.45)',
        display: 'flex', justifyContent: 'center', alignItems: 'flex-start', paddingTop: '12vh',
      }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          width: '560px', maxWidth: '92vw', background: 'var(--surface)',
          border: '1px solid var(--border)', borderRadius: '12px',
          boxShadow: '0 16px 48px rgba(0,0,0,0.35)', overflow: 'hidden',
        }}
      >
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
              setLoading(nextQuery.trim().length >= 2)
              setActive(0)
            }}
            placeholder={t('Jméno, e-mail, telefon, IČO, IBAN…', 'Name, email, phone, reg. no., IBAN…')}
            aria-label={t('Hledat klienty a účty', 'Search parties and accounts')}
            aria-controls="command-palette-results"
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
          {query.trim().length >= 2 && loading && (
            <div role="status" aria-live="polite" style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-tertiary)' }}>
              {t('Hledám klienty a účty…', 'Searching parties and accounts…')}
            </div>
          )}
          {query.trim().length >= 2 && !loading && unavailable && (
            <div role="alert" style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-secondary)' }}>
              <div>{t('Vyhledávání je dočasně nedostupné.', 'Search is temporarily unavailable.')}</div>
              <button
                type="button"
                onClick={() => {
                  setUnavailable(false)
                  setLoading(true)
                  setAttempt(value => value + 1)
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
          {query.trim().length >= 2 && !loading && !unavailable && shown.length === 0 && (
            <div style={{ padding: '20px', textAlign: 'center', fontSize: '13px', color: 'var(--text-tertiary)' }}>
              {t('Nic nenalezeno', 'No results')}
            </div>
          )}
        </div>

        <div style={{ padding: '8px 16px', borderTop: '1px solid var(--border)', display: 'flex', gap: '12px', fontSize: '10px', color: 'var(--text-tertiary)' }}>
          <span>↑↓ {t('výběr', 'select')}</span>
          <span>↵ {t('otevřít', 'open')}</span>
          <span>esc {t('zavřít', 'close')}</span>
        </div>
      </div>
    </div>
  )
}
