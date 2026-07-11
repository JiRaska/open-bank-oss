// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A small operator-local "what did I observe when I checked this" log.
//
// Why: some cockpit screens (e.g. Closings) read live state from a backend that
// is frequently idle or down in the sandbox. When it is down there is nothing to
// show — the run history lives in the service that isn't answering — so an
// operator (or a future agent doing a check-up) has no breadcrumb that the screen
// WAS checked and what state it was in at the time. This hook records each
// distinct observed state to localStorage (per-browser, capped, deduped against
// the immediately-preceding entry) so the page can render a compact trail that
// survives reloads and outages.
//
// It is deliberately NOT a governance data source (admin-ui read-only-consumer
// rule): it never writes to any backend and holds only the operator's own local
// observations. The authoritative run history still comes from the service.

'use client'

import { useCallback, useEffect, useState } from 'react'

export type CheckLogKind = 'ok' | 'warn' | 'error'

export interface CheckLogEntry {
  /** ISO timestamp the observation was recorded. */
  at: string
  kind: CheckLogKind
  /** Stable machine code the consumer maps to localized copy (keeps i18n in the page). */
  code: string
  /** Optional structured detail rendered by the consumer (counts, kind, …). */
  meta?: Record<string, string | number>
  /** Stable signature of the observed state, used to dedupe consecutive polls. */
  sig: string
}

const CAP = 40
const keyFor = (scope: string) => `ob:checklog:${scope}`

function read(scope: string): CheckLogEntry[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(keyFor(scope))
    return raw ? (JSON.parse(raw) as CheckLogEntry[]) : []
  } catch {
    return []
  }
}

export function useCheckLog(scope: string) {
  // Start empty and hydrate from localStorage in an effect so server and first
  // client render agree (no hydration mismatch).
  const [entries, setEntries] = useState<CheckLogEntry[]>([])
  useEffect(() => { setEntries(read(scope)) }, [scope])

  /**
   * Record an observation. `sig` is a stable signature of the observed state;
   * a repeat of the same signature as the newest entry is ignored so a 30s poll
   * loop doesn't flood the log — only genuine transitions are appended.
   */
  const record = useCallback((kind: CheckLogKind, code: string, sig: string, meta?: Record<string, string | number>) => {
    setEntries(prev => {
      if (prev[0]?.sig === sig) return prev
      const entry: CheckLogEntry = { at: new Date().toISOString(), kind, code, meta, sig }
      const next = [entry, ...prev].slice(0, CAP)
      try { window.localStorage.setItem(keyFor(scope), JSON.stringify(next)) } catch { /* quota / private mode */ }
      return next
    })
  }, [scope])

  return { entries, record }
}
