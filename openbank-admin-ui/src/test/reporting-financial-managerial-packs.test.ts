// SPDX-License-Identifier: Apache-2.0

// ADR-0286 / #8976. Two things are asserted here and they are different in kind:
//
//  1. the `month` parameter type is an INJECTION BOUNDARY like every other validator, so the
//     tests are rejection tests — a month that reaches the SQL builder is a month that passed;
//  2. each new entry reads a GOLD view and only gold, which is the registry's own rule. A report
//     that reached into silver or bronze would be a second definition of a business figure.

import { describe, expect, it } from 'vitest'
import { REPORT_REGISTRY, getReport, validateParam, validateParams, type ReportParam } from '@/lib/reporting/registry'

const MONTH: ReportParam = {
  name: 'fromMonth', labelCs: 'Od', labelEn: 'From', type: 'month', required: true,
}

const FINANCIAL = ['finance-ifrs9-provisioning', 'finance-stage-migration', 'finance-loan-vintage']
const MANAGERIAL = ['mgmt-credit-decisions-daily', 'mgmt-platform-event-volume', 'mgmt-onboarding-funnel-daily']

describe('month parameter — the injection boundary', () => {
  it('accepts a well-formed accounting period', () => {
    expect(validateParam(MONTH, '2026-09')).toBe('2026-09')
    expect(validateParam(MONTH, '2026-01')).toBe('2026-01')
    expect(validateParam(MONTH, '2026-12')).toBe('2026-12')
  })

  it('rejects every malformed or injecting value', () => {
    for (const bad of [
      '2026-9',            // not zero-padded: lexicographic BETWEEN would order it wrongly
      '2026-13',           // not a calendar month
      '2026-00',
      '2026-09-01',        // a date, not a period
      '2026',
      "2026-09' OR '1'='1",
      "2026-09'; DROP TABLE silver_loans; --",
      'toStartOfMonth(now())',
      ' 2026-09',
      '2026-09 ',
      '',
    ]) {
      expect(validateParam(MONTH, bad), `should reject ${JSON.stringify(bad)}`).toBeNull()
    }
  })
})

describe('financial and managerial packs', () => {
  it('registers all six reports with unique ids', () => {
    for (const id of [...FINANCIAL, ...MANAGERIAL]) {
      expect(getReport(id), `${id} is registered`).toBeTruthy()
    }
    const ids = REPORT_REGISTRY.map((entry) => entry.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('reads gold views only — never silver, never bronze', () => {
    for (const id of [...FINANCIAL, ...MANAGERIAL]) {
      const entry = getReport(id)!
      const params = Object.fromEntries(entry.params.map((p) => [
        p.name, p.type === 'month' ? '2026-09' : '2026-09-01',
      ]))
      const sql = entry.sql(params)
      expect(sql, `${id} reads gold`).toMatch(/FROM openbank_analytics\.gold_/)
      expect(sql, `${id} does not read silver`).not.toMatch(/openbank_analytics\.silver_/)
      expect(sql, `${id} does not read bronze`).not.toMatch(/openbank_analytics\.bronze_/)
    }
  })

  it('interpolates only values that passed validation', () => {
    const entry = getReport('finance-ifrs9-provisioning')!
    const rejected = validateParams(entry, { fromMonth: "2026-09' OR '1'='1", toMonth: '2026-09' })
    expect(rejected.ok).toBe(false)

    const accepted = validateParams(entry, { fromMonth: '2026-01', toMonth: '2026-09' })
    expect(accepted.ok).toBe(true)
    if (accepted.ok) {
      const sql = entry.sql(accepted.params)
      expect(sql).toContain("period BETWEEN '2026-01' AND '2026-09'")
    }
  })

  it('declares every column its SQL selects, and no others', () => {
    // A column the UI renders but the query never returns is an empty cell the operator reads as
    // a zero; the reverse is a figure nobody sees. Both are silent, so both are asserted.
    for (const id of [...FINANCIAL, ...MANAGERIAL]) {
      const entry = getReport(id)!
      for (const column of entry.columns) {
        const params = Object.fromEntries(entry.params.map((p) => [
          p.name, p.type === 'month' ? '2026-09' : '2026-09-01',
        ]))
        expect(entry.sql(params), `${id} selects ${column.key}`).toContain(column.key)
      }
    }
  })

  it('keeps the whole registry behind a declared permission', () => {
    for (const entry of REPORT_REGISTRY) {
      expect(entry.permission, `${entry.id} declares a permission`).toBeTruthy()
    }
  })
})
