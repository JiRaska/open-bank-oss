// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseReportCatalogue, parseReportResult } from '@/lib/reporting/clientContract'
import { REPORT_REGISTRY } from '@/lib/reporting/registry'

const cataloguePayload = { reports: REPORT_REGISTRY.map(({ sql: _sql, ...report }) => report) }
const catalogue = parseReportCatalogue(cataloguePayload)
const report = catalogue[0]
const row = Object.fromEntries(report.columns.map(column => [column.key, column.format === 'datetime' ? '2026-09-10T02:00:00Z' : column.format === 'number' || column.format === 'money' ? '12.5' : 'value']))
const result = { available: true, reportId: report.id, columns: report.columns, rows: [row], generatedAt: '2026-09-10T02:01:00Z', rowCount: 1, truncated: false }

describe('reporting client evidence contract', () => {
  it('accepts the public registry metadata and a matching result', () => {
    expect(catalogue).toHaveLength(REPORT_REGISTRY.length)
    expect(parseReportResult(result, report)).toEqual(result)
  })

  it.each([
    ['wrong report identity', { reportId: catalogue[1].id }],
    ['mismatched columns', { columns: [...report.columns].reverse() }],
    ['contradictory row count', { rowCount: 2 }],
    ['invalid generation time', { generatedAt: 'today' }],
    ['row field outside the public schema', { rows: [{ ...row, hidden_value: 'must not render' }] }],
  ])('rejects %s', (_label, override) => {
    expect(() => parseReportResult({ ...result, ...override }, report)).toThrow()
  })

  it('rejects duplicate report and column identities', () => {
    expect(() => parseReportCatalogue({ reports: [cataloguePayload.reports[0], cataloguePayload.reports[0]] })).toThrow('Duplicate report')
    expect(() => parseReportCatalogue({ reports: [{ ...cataloguePayload.reports[0], columns: [cataloguePayload.reports[0].columns[0], cataloguePayload.reports[0].columns[0]] }] })).toThrow('Duplicate column')
  })

  it('accepts a truthful unavailable result only when it carries no rows', () => {
    const unavailable = { available: false, reportId: report.id, columns: report.columns, rows: [], generatedAt: null, rowCount: 0, truncated: false }
    expect(parseReportResult(unavailable, report)).toEqual(unavailable)
    expect(() => parseReportResult({ ...unavailable, rows: [row], rowCount: 1 }, report)).toThrow('Contradictory unavailable result')
  })
})
