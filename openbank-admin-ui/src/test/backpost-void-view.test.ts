// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import type { BackfillPlan, BackfillRequest } from '@/components/balance-sheet/contracts'
import {
  backPostRunLabel, formatBusinessDate, formatPragueDateTime, loanOutcomeLabel, personLabel, planSentence,
  summarizePlan, voidStateLabel,
} from '@/components/balance-sheet/voidView'

const cs = (c: string) => c
const en = (_c: string, e: string) => e
const nbsp = (s: string) => s.replace(/[  ]/g, ' ')

const PLAN: BackfillPlan = {
  plan: {
    cutoverDate: '2026-09-25', planHash: 'h', tieOut: [],
    loans: [
      { loanId: 'loan-a', currency: 'CZK', status: 'ACTIVE', unpaidPrincipal: 90000, legs: [
        { kind: 'DISBURSEMENT', amount: 100000, currency: 'CZK', valueDate: '2026-03-02' },
        { kind: 'PRINCIPAL_REPAYMENT', amount: 10000, currency: 'CZK', valueDate: '2026-04-02' },
      ] },
      { loanId: 'loan-b', currency: 'CZK', status: 'ACTIVE', unpaidPrincipal: 50000, legs: [
        { kind: 'DISBURSEMENT', amount: 50000, currency: 'CZK', valueDate: '2026-01-15' },
      ] },
    ],
  },
  executable: true, journalCount: 3,
  glTotals: [
    { code: '1200', currency: 'CZK', debit: 150000, credit: 10000, net: 140000 },
    { code: '2100', currency: 'CZK', debit: 10000, credit: 150000, net: -140000 },
  ],
}

describe('back-post void — human-readable view', () => {
  it('labels every request state in plain language, never the enum', () => {
    for (const s of ['PROPOSED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'EXECUTED'] as const) {
      expect(voidStateLabel(s, cs)).not.toMatch(/^[A-Z_]+$/)
      expect(voidStateLabel(s, en)).not.toMatch(/^[A-Z_]+$/)
    }
    expect(voidStateLabel('PROPOSED', cs)).toBe('Čeká na schválení')
    expect(voidStateLabel('APPROVED', cs)).toBe('Schváleno, čeká na provedení')
    expect(voidStateLabel('REJECTED', cs)).toBe('Zamítnuto')
    expect(voidStateLabel('EXECUTED', cs)).toBe('Provedeno')
    expect(loanOutcomeLabel('VOIDED', cs)).toBe('Stornováno')
  })

  it('summarises the dry-run as one sentence with the total and currency', () => {
    const s = summarizePlan(PLAN)
    expect(s.loanCount).toBe(2)
    expect(s.totalByCurrency).toEqual({ CZK: 160000 })
    expect(s.disbursedByCurrency).toEqual({ CZK: 150000 })
    expect([s.firstValueDate, s.lastValueDate]).toEqual(['2026-01-15', '2026-04-02'])
    expect(s.loans[0]).toMatchObject({ loanId: 'loan-a', disbursedOn: '2026-03-02', disbursed: 100000, entries: 2 })
    expect(nbsp(planSentence(s, cs, 'cs-CZ'))).toBe('Storno vrátí 2 úvěrů a vytvoří 3 protizápisů v celkové výši 160 000,00 Kč.')
  })

  it('never sums across currencies', () => {
    const s = summarizePlan({ ...PLAN, glTotals: [...PLAN.glTotals, { code: '1200', currency: 'EUR', debit: 5, credit: 0, net: 5 }] })
    expect(s.totalByCurrency).toEqual({ CZK: 160000, EUR: 5 })
    expect(nbsp(planSentence(s, en, 'en-GB'))).toContain('CZK 160,000.00 + €5.00')
  })

  it('identifies a back-post run by when, how much and who — not by its id', () => {
    const run = {
      id: '01a0dcb0-0000-4000-8000-000000000000', state: 'EXECUTED', cutoverDate: '2026-09-25', planHash: 'h',
      loanCount: 44, legCount: 352, proposedBy: 'admin@openbank.local', decidedBy: 'x', decisionReason: null,
      executedBy: 'admin@openbank.local', executedAt: '2026-09-26T07:49:49Z',
    } as BackfillRequest
    const label = nbsp(backPostRunLabel(run, cs, 'cs-CZ'))
    expect(label).toBe('Doúčtování z 26. 9. 2026 9:49 — 44 úvěrů, 352 zápisů, provedl(a) admin@openbank.local')
    expect(label).not.toContain('01a0dcb0')
  })

  it('renders times in Prague and business dates without a day shift', () => {
    expect(nbsp(formatPragueDateTime('2026-01-10T23:30:00Z', 'cs-CZ'))).toBe('11. 1. 2026 0:30')
    expect(nbsp(formatBusinessDate('2026-09-30', 'cs-CZ'))).toBe('30. 9. 2026')
    expect(formatPragueDateTime(null, 'cs-CZ')).toBe('—')
  })

  it('labels an identity for what it is instead of inventing a name', () => {
    expect(personLabel('compliance2@openbank.local', cs)).toEqual({ text: 'compliance2@openbank.local', kind: 'e-mail' })
    expect(personLabel('petr.finance', en)).toEqual({ text: 'petr.finance', kind: 'user' })
    expect(personLabel(null, cs)).toBeNull()
  })
})
