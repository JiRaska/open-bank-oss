// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Hand a list row to its detail page without a round-trip.
//
// When an operator clicks a row we already hold the full record the list
// fetched. Rather than force the detail page to re-fetch (and depend on a
// by-id backend endpoint that may not exist for every service), we stash the
// row in sessionStorage keyed by namespace+id and read it back on the detail
// page. The detail page still attempts a best-effort by-id refresh, so a direct
// URL / hard refresh (empty stash) degrades gracefully instead of breaking.
//
// sessionStorage (not localStorage): the handoff is meant to live for the
// navigation, not to persist across sessions, and it is per-tab.

const KEY = (ns: string, id: string) => `ob:row:${ns}:${id}`

export function stashRow(ns: string, id: string, row: unknown): void {
  if (typeof window === 'undefined') return
  try {
    window.sessionStorage.setItem(KEY(ns, id), JSON.stringify(row))
  } catch {
    // Quota / private-mode / serialization failure — the detail page falls back
    // to its by-id fetch, so a missing stash is never fatal.
  }
}

export function readStashedRow<T>(ns: string, id: string): T | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(KEY(ns, id))
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}
