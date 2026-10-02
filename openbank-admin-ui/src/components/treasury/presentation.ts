// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// How a treasury deal reads to a dealer / back-office person: business events in words, actors as
// people or named systems, limit figures as money, ledger postings as "what was booked to which
// account" — the idempotency keys and UUIDs stay available, but never as the primary content.

import type { LedgerJournalEntry } from '@/lib/ledger/ledgerJournalContract'
import { formatMoneyCompact } from '@/lib/format/money'
import { czechPlural, englishPlural } from '@/lib/i18n/plural'

type T = (cs: string, en: string) => string

/** The posting event's ISO-date suffix (`treasury:<id>:accrued:2026-09-27`), if any. */
export function eventDate(idempotencyKey: string): string | null {
  const m = /:(\d{4}-\d{2}-\d{2})$/.exec(idempotencyKey)
  return m ? m[1] : null
}

function shortDate(iso: string, locale: string): string {
  const d = new Date(`${iso}T00:00:00Z`)
  return d.toLocaleDateString(locale, { day: 'numeric', month: 'numeric', timeZone: 'UTC' })
}

/** `settled` → "Vypořádání", `accrued` (+ date) → "Naběhlý úrok za 27. 9.", unknown → raw. */
export function postingEventLabel(event: string, idempotencyKey: string, t: T, locale: string): string {
  switch (event) {
    case 'settled': return t('Vypořádání', 'Settlement')
    case 'matured': return t('Splatnost', 'Maturity')
    case 'reversed': return t('Storno', 'Reversal')
    case 'accrued': {
      const d = eventDate(idempotencyKey)
      return d ? t(`Naběhlý úrok za ${shortDate(d, locale)}`, `Interest accrued for ${shortDate(d, locale)}`) : t('Naběhlý úrok', 'Interest accrued')
    }
    default: return event
  }
}

/** Names of the treasury chart (ledger V29/V30, `TreasuryChart` in treasury-service). */
const GL_NAMES: Record<string, [string, string]> = {
  '1001': ['Nostro CZK', 'Nostro CZK'],
  '1002': ['Nostro EUR', 'Nostro EUR'],
  '1500': ['Umístění na peněžním trhu CZK', 'MM placements CZK'],
  '1501': ['Umístění na peněžním trhu EUR', 'MM placements EUR'],
  '1510': ['Depozitní facilita ČNB', 'ČNB deposit facility'],
  '1520': ['Naběhlé úroky — pohledávka CZK', 'Accrued interest receivable CZK'],
  '1521': ['Naběhlé úroky — pohledávka EUR', 'Accrued interest receivable EUR'],
  '1990': ['FX pozice CZK', 'FX position CZK'],
  '1991': ['FX pozice EUR', 'FX position EUR'],
  '2300': ['Přijaté výpůjčky od bank CZK', 'MM borrowings CZK'],
  '2301': ['Přijaté výpůjčky od bank EUR', 'MM borrowings EUR'],
  '2310': ['Naběhlé úroky — závazek CZK', 'Accrued interest payable CZK'],
  '2311': ['Naběhlé úroky — závazek EUR', 'Accrued interest payable EUR'],
  '2320': ['Lombardní úvěr ČNB', 'ČNB lombard borrowing'],
  '4200': ['Úrokové výnosy z peněžního trhu CZK', 'MM interest income CZK'],
  '4201': ['Úrokové výnosy z peněžního trhu EUR', 'MM interest income EUR'],
  '5200': ['Úrokové náklady peněžního trhu CZK', 'MM interest expense CZK'],
  '5201': ['Úrokové náklady peněžního trhu EUR', 'MM interest expense EUR'],
}

/** The treasury GL ids are seeded as `a0000000-0000-0000-0000-00000000<code>`. */
export function glCode(glAccountId: string): string | null {
  const m = /^a0000000-0000-0000-0000-0{8}(\d{4})$/i.exec(glAccountId)
  return m ? m[1] : null
}

export type PostingAccount = { code: string | null; name: string; amount: number; currency: string }

export function glAccount(glAccountId: string, amount: number, currency: string, t: T): PostingAccount {
  const code = glCode(glAccountId)
  const names = code ? GL_NAMES[code] : undefined
  return { code, name: names ? t(names[0], names[1]) : (code ?? t('Neznámý účet', 'Unknown account')), amount, currency }
}

export type PostingRow = {
  key: string
  label: string
  postedAt: string
  journalId: string
  idempotencyKey: string
  /** Ledger sequence number when the ledger detail loaded. */
  entryNumber: number | null
  /** Journal total (sum of debits) per currency, when the ledger detail loaded. */
  totals: { amount: number; currency: string }[]
  debits: PostingAccount[]
  credits: PostingAccount[]
}

export function postingRow(
  ref: { event: string; idempotencyKey: string; journalId: string; postedAt: string },
  entry: LedgerJournalEntry | undefined,
  t: T,
  locale: string,
): PostingRow {
  const lines = entry?.lines ?? []
  const debits = lines.filter(l => l.side === 'DEBIT').map(l => glAccount(l.glAccountId, l.amount, l.currencyCode, t))
  const credits = lines.filter(l => l.side === 'CREDIT').map(l => glAccount(l.glAccountId, l.amount, l.currencyCode, t))
  const totalsBy = new Map<string, number>()
  for (const d of debits) totalsBy.set(d.currency, (totalsBy.get(d.currency) ?? 0) + d.amount)
  return {
    key: ref.idempotencyKey,
    label: postingEventLabel(ref.event, ref.idempotencyKey, t, locale),
    postedAt: ref.postedAt,
    journalId: ref.journalId,
    idempotencyKey: ref.idempotencyKey,
    entryNumber: entry?.entryNumber ?? null,
    totals: [...totalsBy].map(([currency, amount]) => ({ amount: Math.round(amount * 100) / 100, currency })),
    debits,
    credits,
  }
}

/** Actor kind in words; `system:simulated-market` gets its own name. */
export function actorTypeLabel(actorType: string, actor: string, t: T): string {
  switch (actorType) {
    case 'HUMAN': return t('Uživatel', 'User')
    case 'AI_AGENT': return t('AI agent', 'AI agent')
    case 'SERVICE': return t('Služba', 'Service')
    case 'SYSTEM':
      if (actor === 'system:simulated-market') return t('Systém — simulovaný trh', 'System — simulated market')
      if (actor.startsWith('system:')) return t(`Systém — ${actor.slice(7)}`, `System — ${actor.slice(7)}`)
      return t('Systém', 'System')
    default: return actorType
  }
}

/** Who acted, for a person reader: a system needs no id beside its name; a user is their login. */
export function actorDisplay(actor: string, actorType: string, t: T): string {
  if (actorType === 'SYSTEM') return actorTypeLabel(actorType, actor, t)
  return actor
}

export type LimitSnapshot = { limit: number; currency: string; exposureAfter: number; headroomAfter: number }

/** "Limit 100 mld. Kč · expozice po obchodu 20 mil. Kč · volný limit 99,98 mld. Kč" */
export function limitSnapshotText(s: LimitSnapshot, t: T, locale: string): string {
  const m = (v: number) => formatMoneyCompact(v, s.currency, locale)
  return t(
    `Limit ${m(s.limit)} · expozice po obchodu ${m(s.exposureAfter)} · volný limit ${m(s.headroomAfter)}`,
    `Limit ${m(s.limit)} · exposure after deal ${m(s.exposureAfter)} · headroom ${m(s.headroomAfter)}`,
  )
}

/** "2 dny" / "1 day". */
export function daysText(n: number, t: T): string {
  return t(`${n} ${czechPlural(n, 'den', 'dny', 'dní')}`, `${n} ${englishPlural(n, 'day', 'days')}`)
}
