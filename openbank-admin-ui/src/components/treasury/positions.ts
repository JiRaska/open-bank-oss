// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Treasury daily position (ADR-0315): the pure parts of the page — date shortcuts, the basis a
// date gets, and whether a loaded answer is empty. Calendar arithmetic on ISO dates in UTC, so a
// browser zone never shifts a day; "today" itself is the bank's (Europe/Prague) via bankToday().

import type { PositionBasis, Positions } from './contracts'

/** [iso] plus [days] calendar days, ISO in and out. */
export function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export type DateShortcut = { days: number; cs: string; en: string }

export const DATE_SHORTCUTS: readonly DateShortcut[] = [
  { days: 0, cs: 'Dnes', en: 'Today' },
  { days: 1, cs: 'Zítra', en: 'Tomorrow' },
  { days: 7, cs: '+7 dní', en: '+7 days' },
  { days: 30, cs: '+30 dní', en: '+30 days' },
]

/** The service's basis when it states one; otherwise derived the same way (after today = projection). */
export function basisOf(data: Positions, today: string): PositionBasis {
  if (data.basis) return data.basis
  return data.asOf > (data.today ?? today) ? 'PROJECTED' : 'ACTUAL'
}

/** No deal is outstanding that day: every currency is zero (and no deal counted, when the service says). */
export function isEmptyPosition(data: Positions): boolean {
  return data.positions.every(p =>
    (p.dealCount ?? 0) === 0 && p.placed === 0 && p.borrowed === 0 && p.atCnb === 0)
}

/** The chosen day spelt out in the UI language ("čtvrtek 1. října 2026"), whatever the browser locale. */
export function longDate(iso: string, language: 'cs' | 'en'): string {
  return new Intl.DateTimeFormat(language === 'cs' ? 'cs-CZ' : 'en-GB', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC',
  }).format(new Date(`${iso}T00:00:00Z`))
}
