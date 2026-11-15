// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { ceuFilings, latestByCode, type StatutoryReturn } from '@/components/regulatory/statutoryReturns'

const row = (entityId: string, revision: number, period = '2026-11-09'): StatutoryReturn => ({
  id: `${entityId}-${revision}-${period}`, catalogueId: 'cz-ceu', returnCode: 'CEUSIFE10',
  entityId, revision, period, status: 'ASSEMBLED', dueDate: '2026-11-10',
  submittedAt: null, submissionReference: null,
})

describe('filing identity includes entity', () => {
  it('preserves another entity even when its latest period and revision are older', () => {
    const rows = [row('a', 1), row('a', 2), row('b', 1, '2026-11-08')]
    expect(latestByCode(rows).get('cz-ceu/CEUSIFE10')?.map(r => r.id)).toEqual([rows[1].id, rows[2].id])
  })
  it('preserves both CEU entities in the same window, excluding another catalogue', () => {
    const rows = [row('a', 1), row('b', 1), row('a', 2)]
    const filings = ceuFilings([...rows, { ...row('c', 9), catalogueId: 'other' }])
    expect(filings).toHaveLength(2)
    expect(filings.map(r => [r.entityId, r.revision])).toEqual([['a', 2], ['b', 1]])
    expect(filings.every(r => r.changes === null)).toBe(true)
  })
})
