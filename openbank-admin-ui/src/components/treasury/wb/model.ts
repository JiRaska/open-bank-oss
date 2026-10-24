// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure view logic of the treasury workbench pages (kept out of the page files, which may export
// only the page itself, and unit-tested without rendering).
import { addDays } from '@/components/treasury/positions'
import type { DealState } from '@/components/treasury/contracts'

export type Preset = 'yesterday' | 'today' | 'tomorrow' | 'week' | 'month' | 'custom'

/** The [from, to] range a preset covers around [today] (inclusive; week = today + 6, month = today + 29). */
export function presetRange(preset: Exclude<Preset, 'custom'>, today: string): { from: string; to: string } {
  switch (preset) {
    case 'yesterday': return { from: addDays(today, -1), to: addDays(today, -1) }
    case 'today': return { from: today, to: today }
    case 'tomorrow': return { from: addDays(today, 1), to: addDays(today, 1) }
    case 'week': return { from: today, to: addDays(today, 6) }
    case 'month': return { from: today, to: addDays(today, 29) }
  }
}

export type DealFilters = {
  state: DealState | ''
  instrument: string
  bookId: string
  counterpartyId: string
  currency: string
  kind: string
  hideMatured: boolean
  hideAuthorised: boolean
  text: string
  tradeDateFrom: string
  tradeDateTo: string
  maturityFrom: string
  maturityTo: string
}

export const NO_DEAL_FILTERS: DealFilters = {
  state: '', instrument: '', bookId: '', counterpartyId: '', currency: '', kind: '', hideMatured: false, hideAuthorised: false,
  text: '', tradeDateFrom: '', tradeDateTo: '', maturityFrom: '', maturityTo: '',
}

/** Only the filters that are set are sent (state first), so an untouched blotter asks for plain /deals. */
export function dealFilterQuery(f: DealFilters): Record<string, string> {
  const q: Record<string, string> = {}
  for (const [k, v] of Object.entries(f)) {
    if (typeof v === 'boolean') { if (v) q[k] = 'true' } else if (v.trim() !== '') q[k] = v.trim()
  }
  return q
}

/** Rebuild filters from a saved view (unknown keys ignored, wrong types dropped). */
export function filtersFromSaved(raw: Record<string, unknown>): DealFilters {
  const next: DealFilters = { ...NO_DEAL_FILTERS }
  for (const k of Object.keys(NO_DEAL_FILTERS) as (keyof DealFilters)[]) {
    const v = raw[k]
    if (typeof NO_DEAL_FILTERS[k] === 'boolean') (next[k] as boolean) = v === true || v === 'true'
    else if (typeof v === 'string') (next[k] as string) = v
  }
  return next
}

/** Minimal CSV reader (quoted cells, comma or semicolon) → header-keyed rows. */
export function parseCsv(text: string): Record<string, string>[] {
  const lines = text.split(/\r?\n/).filter(l => l.trim() !== '')
  if (lines.length < 2) return []
  const split = (line: string): string[] => {
    const out: string[] = []
    let cur = ''
    let quoted = false
    const sep = line.includes(';') && !line.includes(',') ? ';' : ','
    for (let i = 0; i < line.length; i += 1) {
      const c = line[i]
      if (quoted) { if (c === '"' && line[i + 1] === '"') { cur += '"'; i += 1 } else if (c === '"') quoted = false; else cur += c }
      else if (c === '"') quoted = true
      else if (c === sep) { out.push(cur); cur = '' }
      else cur += c
    }
    out.push(cur)
    return out.map(x => x.trim())
  }
  const header = split(lines[0])
  return lines.slice(1).map(l => { const cells = split(l); return Object.fromEntries(header.map((h, i) => [h, cells[i] ?? ''])) })
}
