// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import {
  backfillActions, bankToday, cumulativeGap, isIsoDate, ladderRows, parseQuotes, principalNameFromToken,
} from '@/components/balance-sheet/model'
import { backfillPlanSchema, cashFlowsSchema, snapshotListSchema } from '@/components/balance-sheet/contracts'
import type { BackfillRequest } from '@/components/balance-sheet/contracts'

const token = (claims: Record<string, unknown>) =>
  `h.${btoa(JSON.stringify(claims)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}.s`

const request = (over: Partial<BackfillRequest> = {}): BackfillRequest => ({
  id: 'r1', state: 'PROPOSED', cutoverDate: '2026-09-25', planHash: 'h', loanCount: 2, legCount: 13,
  proposedBy: 'maker', decidedBy: null, decisionReason: null, executedBy: null, ...over,
})

describe('maturity ladder', () => {
  const flows = {
    currency: 'CZK', discountIndex: 'CZEONIA', positions: 3, priced: true, total: -50, presentValue: -49.8,
    buckets: [{ bucket: 'overnight', amount: -300 }, { bucket: '1-3M', amount: 250 }],
  }
  it('keeps the engine bucket order, splits sign into inflow/outflow, and never both', () => {
    const rows = ladderRows(flows)
    expect(rows[0]).toEqual({ bucket: 'overnight', inflow: 0, outflow: -300, net: -300 })
    expect(rows.find(r => r.bucket === '1-3M')).toEqual({ bucket: '1-3M', inflow: 250, outflow: 0, net: 250 })
    expect(rows.map(r => r.bucket).slice(0, 3)).toEqual(['overnight', '<1M', '1-3M'])
    expect(rows.every(r => r.inflow === 0 || r.outflow === 0)).toBe(true)
  })
  it('keeps a bucket label the UI does not know instead of dropping its money', () => {
    const rows = ladderRows({ ...flows, buckets: [...flows.buckets, { bucket: '>30Y', amount: 7 }] })
    expect(rows.at(-1)).toEqual({ bucket: '>30Y', inflow: 7, outflow: 0, net: 7 })
  })
  it('accumulates the gap', () => {
    expect(cumulativeGap(ladderRows(flows)).slice(0, 3)).toEqual([-300, -300, -50])
  })
})

describe('principal name — the identity lending records as maker/checker', () => {
  it('follows Quarkus principal-claim order: upn, preferred_username, sub', () => {
    expect(principalNameFromToken(token({ upn: 'u', preferred_username: 'p', sub: 's' }))).toBe('u')
    expect(principalNameFromToken(token({ preferred_username: 'jana.finance', sub: 's' }))).toBe('jana.finance')
    expect(principalNameFromToken(token({ sub: 's-1' }))).toBe('s-1')
  })
  it('returns null for a missing or unreadable token', () => {
    expect(principalNameFromToken(undefined)).toBeNull()
    expect(principalNameFromToken('not-a-jwt')).toBeNull()
    expect(principalNameFromToken('a.%%%.b')).toBeNull()
  })
})

describe('four-eyes actions', () => {
  const all = { decide: true, execute: true }
  it('hides decide from the proposer', () => {
    expect(backfillActions(request(), 'maker', all)).toEqual({ canDecide: false, canExecute: false, decideBlockedBy: 'own-proposal' })
  })
  it('offers decide to a different finance user', () => {
    expect(backfillActions(request(), 'checker', all).canDecide).toBe(true)
  })
  it('never hides decide when the actor is unknown — the server 422 is the control', () => {
    expect(backfillActions(request(), null, all).canDecide).toBe(true)
  })
  it('offers execute only on an APPROVED request, to anyone holding the permission (including the maker)', () => {
    expect(backfillActions(request({ state: 'APPROVED' }), 'maker', all)).toEqual({ canDecide: false, canExecute: true, decideBlockedBy: 'state' })
    expect(backfillActions(request({ state: 'EXECUTED' }), 'x', all).canExecute).toBe(false)
    expect(backfillActions(request({ state: 'APPROVED' }), 'x', { decide: false, execute: false })).toEqual({ canDecide: false, canExecute: false, decideBlockedBy: 'permission' })
  })
})

describe('curve quote parsing', () => {
  it('converts percent to the decimal simple rate and groups by index', () => {
    const r = parseQuotes('CZEONIA ON 3.5\nczeonia 3m 3,6\n# comment\n\nESTR;1Y;2.25%')
    expect(r).toEqual({ ok: true, count: 3, curves: { CZEONIA: [{ tenor: 'ON', rate: 0.035 }, { tenor: '3M', rate: 0.036 }], ESTR: [{ tenor: '1Y', rate: 0.0225 }] } })
  })
  it('reports every bad line with its reason', () => {
    const r = parseQuotes('LIBOR 3M 1\nCZEONIA 3Q 1\nCZEONIA ON abc\nCZEONIA ON 1\nCZEONIA ON 2\nCZEONIA ON')
    expect(r.ok).toBe(false)
    if (!r.ok) expect(r.errors.map(e => `${e.line}:${e.code}`)).toEqual(['1:index', '2:tenor', '3:rate', '5:duplicate', '6:format'])
  })
  it('refuses an empty upload', () => {
    expect(parseQuotes('# nothing')).toEqual({ ok: false, errors: [{ line: 0, code: 'empty' }] })
  })
})

describe('dates', () => {
  it('validates calendar dates', () => {
    expect(isIsoDate('2026-02-28')).toBe(true)
    expect(isIsoDate('2026-02-30')).toBe(false)
    expect(isIsoDate('30.9.2026')).toBe(false)
  })
  it('computes the bank day in Europe/Prague', () => {
    expect(bankToday(new Date('2026-09-24T22:30:00Z'))).toBe('2026-09-25')
  })
})

describe('wire contracts accept the backends’ documented shapes', () => {
  it('parses a run list, a cash-flow projection and a backfill plan', () => {
    expect(snapshotListSchema.safeParse({ runs: [{ id: 'a', asOf: '2026-09-30', recordedAt: '2026-09-30T06:00:00Z', provenance: 'synthetic', status: 'UNTIED', positionCount: 3, mismatchCount: 1 }] }).success).toBe(true)
    expect(cashFlowsSchema.safeParse({
      runId: 'a', asOf: '2026-09-30', provenance: 'synthetic', curveSetId: 'c', curveSetProvenance: 'production',
      model: { id: 'nmd', version: '1', coreRatio: 0.6, coreRunoffYears: 5, annualDepositRate: 0 },
      expandedPositions: 3, notExpanded: 2, notExpandedReason: 'r', unpriced: ['USD'],
      currencies: [{ currency: 'CZK', discountIndex: 'CZEONIA', positions: 3, priced: true, buckets: [], total: 0, presentValue: 0 }],
    }).success).toBe(true)
    expect(backfillPlanSchema.safeParse({
      plan: { cutoverDate: '2026-09-25', planHash: 'h', tieOut: [{ currency: 'CZK', loansReceivableAfter: 100, lendingUnpaidPrincipal: 100, ties: true }], loans: [{ loanId: 'l', currency: 'CZK', status: 'ACTIVE', unpaidPrincipal: 100, unsupportedReason: null, legs: [] }], legs: [], unsupported: [], executable: true },
      executable: true, journalCount: 13, glTotals: [{ code: '1200', currency: 'CZK', debit: 300, credit: 200, net: 100 }],
    }).success).toBe(true)
  })
})
