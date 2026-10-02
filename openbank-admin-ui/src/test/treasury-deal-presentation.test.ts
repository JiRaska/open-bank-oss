// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { czechPlural } from '@/lib/i18n/plural'
import { formatMoneyCompact } from '@/lib/format/money'
import { shortReference } from '@/components/ui/HumanReference'
import { actorDisplay, actorTypeLabel, daysText, glCode, limitSnapshotText, postingEventLabel, postingRow } from '@/components/treasury/presentation'
import type { LedgerJournalEntry } from '@/lib/ledger/ledgerJournalContract'

const cs = (c: string) => c
const en = (_c: string, e: string) => e
const nbsp = (s: string) => s.replace(/ | /g, ' ')

describe('Czech plural', () => {
  it.each([[0, 'dní'], [1, 'den'], [2, 'dny'], [4, 'dny'], [5, 'dní'], [11, 'dní'], [22, 'dní']])('%i → %s', (n, form) => {
    expect(czechPlural(n, 'den', 'dny', 'dní')).toBe(form)
  })
  it('days text in both languages', () => {
    expect(daysText(2, cs)).toBe('2 dny')
    expect(daysText(1, cs)).toBe('1 den')
    expect(daysText(5, cs)).toBe('5 dní')
    expect(daysText(1, en)).toBe('1 day')
  })
})

describe('limit note', () => {
  it('renders the structured figures as compact Czech money', () => {
    const text = nbsp(limitSnapshotText({ limit: 100_000_000_000, currency: 'CZK', exposureAfter: 20_000_000, headroomAfter: 99_980_000_000 }, cs, 'cs-CZ'))
    expect(text).toBe('Limit 100 mld. Kč · expozice po obchodu 20 mil. Kč · volný limit 99,98 mld. Kč')
  })
  it('falls back for an unknown currency instead of throwing', () => {
    expect(formatMoneyCompact(5, 'XX', 'cs-CZ')).toContain('XX')
  })
})

describe('actors', () => {
  it('names the simulated market and translates actor types', () => {
    expect(actorTypeLabel('SYSTEM', 'system:simulated-market', cs)).toBe('Systém — simulovaný trh')
    expect(actorDisplay('system:simulated-market', 'SYSTEM', cs)).toBe('Systém — simulovaný trh')
    expect(actorTypeLabel('HUMAN', 'dealer@openbank.local', cs)).toBe('Uživatel')
    expect(actorDisplay('dealer@openbank.local', 'HUMAN', cs)).toBe('dealer@openbank.local')
  })
})

describe('postings', () => {
  const key = 'treasury:01a0dca3-fbe3-7b3b-b780-1c602590730c'
  it('labels business events in Czech, with the accrual day', () => {
    expect(postingEventLabel('settled', `${key}:settled`, cs, 'cs-CZ')).toBe('Vypořádání')
    expect(postingEventLabel('accrued', `${key}:accrued:2026-09-27`, cs, 'cs-CZ')).toBe('Naběhlý úrok za 27. 9.')
    expect(postingEventLabel('matured', `${key}:matured`, cs, 'cs-CZ')).toBe('Splatnost')
    expect(postingEventLabel('something-new', `${key}:x`, cs, 'cs-CZ')).toBe('something-new')
  })

  it('maps a ledger journal to named debit/credit accounts and a total', () => {
    const line = (code: string, side: 'DEBIT' | 'CREDIT', amount: number, sequence: number) => ({
      id: `l${sequence}`, glAccountId: `a0000000-0000-0000-0000-00000000${code}`, side, amount,
      currencyCode: 'CZK', baseAmount: amount, baseCurrencyCode: 'CZK', sequence, subAccountId: null,
    })
    const entry: LedgerJournalEntry = {
      id: '8dd03ec8-7bf7-4d0b-ae53-1c13eb300149', entryNumber: 4812, transactionId: 't', entryDate: '2026-09-26',
      valueDate: '2026-09-26', description: null, status: 'POSTED', createdAt: '2026-09-26T07:40:15Z', synthetic: false,
      lines: [line('1500', 'DEBIT', 20_000_000, 1), line('1001', 'CREDIT', 20_000_000, 2)],
    }
    const row = postingRow({ event: 'settled', idempotencyKey: `${key}:settled`, journalId: entry.id, postedAt: entry.createdAt }, entry, cs, 'cs-CZ')
    expect(row.label).toBe('Vypořádání')
    expect(row.entryNumber).toBe(4812)
    expect(row.totals).toEqual([{ amount: 20_000_000, currency: 'CZK' }])
    expect(row.debits).toEqual([{ code: '1500', name: 'Umístění na peněžním trhu CZK', amount: 20_000_000, currency: 'CZK' }])
    expect(row.credits[0]).toMatchObject({ code: '1001', name: 'Nostro CZK' })
  })

  it('without the ledger detail the row still reads, with no accounts', () => {
    const row = postingRow({ event: 'matured', idempotencyKey: `${key}:matured`, journalId: 'j', postedAt: '2026-09-26T07:40:15Z' }, undefined, cs, 'cs-CZ')
    expect(row).toMatchObject({ label: 'Splatnost', entryNumber: null, totals: [], debits: [], credits: [] })
  })

  it('recognises only the seeded treasury GL ids', () => {
    expect(glCode('a0000000-0000-0000-0000-000000001001')).toBe('1001')
    expect(glCode('8dd03ec8-7bf7-4d0b-ae53-1c13eb300149')).toBeNull()
  })

  it('shortens a UUID reference', () => {
    expect(shortReference('8dd03ec8-7bf7-4d0b-ae53-1c13eb300149')).toBe('8dd03ec8…')
  })
})
