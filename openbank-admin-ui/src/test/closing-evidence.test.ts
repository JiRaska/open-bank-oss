// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { parseCloseFailures, parseCloseRun, parseCloseRuns, parseReconciliationReport } from '@/lib/closings/evidence'

const run = {
  id: 'd2b7e9a0-0000-4000-8000-000000000001', trigger: 'SCHEDULED', status: 'COMPLETED', periodFrom: '2026-08-01', periodTo: '2026-08-31',
  accountsEnumerated: 2, pocketsClosed: 3, pocketsFailed: 0, pocketsSkipped: 0,
  startedAt: '2026-09-01T00:30:00Z', finishedAt: '2026-09-01T00:30:05Z',
} as const

describe('closing cockpit evidence boundary', () => {
  it('retains valid reconciliation rows and counts invalid financial evidence', () => {
    const result = parseReconciliationReport({
      asOf: '2026-09-08', generatedAt: '2026-09-08T23:30:00Z', tolerance: '0.01',
      currencies: [
        { currency: 'EUR', ledgerControlBalance: '10.00', subLedgerBookedSum: 10, difference: 0, withinTolerance: true },
        { currency: 'USD', ledgerControlBalance: 'not-a-number', subLedgerBookedSum: 2, difference: 0, withinTolerance: true },
      ],
    })
    expect(result.value?.currencies).toHaveLength(1)
    expect(result.excludedCount).toBe(1)
  })

  it('rejects an invalid reconciliation envelope', () => {
    expect(parseReconciliationReport({ currencies: [] }).value).toBeNull()
    expect(parseReconciliationReport({ asOf: '2026-02-30', generatedAt: '2026-03-01T00:00:00Z', tolerance: 0, currencies: [] }).value).toBeNull()
  })

  it('never presents contradictory or duplicate currency evidence as a valid tie-out', () => {
    const row = { currency: 'EUR', ledgerControlBalance: 10, subLedgerBookedSum: 11, difference: 1, withinTolerance: false }
    expect(parseReconciliationReport({
      asOf: '2026-09-08', generatedAt: '2026-09-08T23:30:00Z', tolerance: 2,
      currencies: [row],
    })).toEqual({ value: { asOf: '2026-09-08', generatedAt: '2026-09-08T23:30:00Z', tolerance: 2, currencies: [] }, excludedCount: 1 })
    expect(parseReconciliationReport({
      asOf: '2026-09-08', generatedAt: '2026-09-08T23:30:00Z', tolerance: 0,
      currencies: [row, row],
    }).value).toBeNull()
  })

  it('validates list rows and the accepted manual run', () => {
    expect(parseCloseRuns([run, { ...run, status: 'SURPRISE' }])).toEqual({ value: [run], excludedCount: 1 })
    expect(parseCloseRun(run)).toEqual(run)
    expect(parseCloseRun({ ...run, pocketsClosed: -1 })).toBeNull()
    expect(parseCloseRun({ ...run, startedAt: 'not-a-date' })).toBeNull()
    expect(parseCloseRun({ ...run, status: 'RUNNING', finishedAt: run.finishedAt })).toBeNull()
    expect(parseCloseRun({ ...run, status: 'COMPLETED_WITH_FAILURES', pocketsFailed: 0 })).toBeNull()
  })

  it('sorts history by verified start time and excludes ambiguous duplicate identities', () => {
    const newer = { ...run, id: 'd2b7e9a0-0000-4000-8000-000000000002', startedAt: '2026-09-02T00:30:00Z', finishedAt: '2026-09-02T00:30:05Z' }
    expect(parseCloseRuns([run, newer]).value?.map(item => item.id)).toEqual([newer.id, run.id])
    expect(parseCloseRuns([run, run, newer])).toEqual({ value: [newer], excludedCount: 2 })
  })

  it('validates failure evidence without discarding valid siblings', () => {
    const failure = {
      id: 'f0000000-0000-4000-8000-000000000001', runId: run.id,
      accountId: 'a0000000-0000-4000-8000-000000000001', pocketCurrency: 'EUR',
      periodFrom: '2026-08-01', periodTo: '2026-08-31', reason: 'UPSTREAM', detail: null,
      failedAt: '2026-09-01T00:30:03Z',
    }
    expect(parseCloseFailures([failure, { ...failure, reason: 'MADE_UP' }], run.id)).toEqual({ value: [failure], excludedCount: 1 })
    expect(parseCloseFailures([{ ...failure, runId: 'd2b7e9a0-0000-4000-8000-000000000099' }], run.id)).toEqual({ value: [], excludedCount: 1 })
    expect(parseCloseFailures([failure, failure], run.id)).toEqual({ value: [], excludedCount: 2 })
    expect(parseCloseFailures([{ ...failure, periodFrom: '2026-09-02', periodTo: '2026-09-01' }], run.id)).toEqual({ value: [], excludedCount: 1 })
    expect(parseCloseFailures({ failures: [] }).value).toBeNull()
  })
})
