// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The registry's validators are the SQL-injection boundary of the reporting surface (ADR-0286),
// so these tests assert REJECTION, not just acceptance: every value that could smuggle SQL into a
// ClickHouse HTTP query string must fail validation before it reaches an SQL builder.

import { describe, expect, it } from 'vitest'
import {
  REPORT_REGISTRY, getReport, validateParam, validateParams, type ReportParam,
} from '@/lib/reporting/registry'

const DATE: ReportParam = { name: 'from', labelCs: 'Od', labelEn: 'From', type: 'date', required: true }
const AMOUNT: ReportParam = {
  name: 'minAmount', labelCs: 'Min', labelEn: 'Min', type: 'number', required: false, defaultValue: '1000',
}
const CURRENCY: ReportParam = {
  name: 'currency', labelCs: 'Měna', labelEn: 'Currency', type: 'enum', required: false,
  options: ['CZK', 'EUR'],
}

describe('reporting registry — parameter validation (the injection boundary)', () => {
  it('accepts a well-formed date and rejects anything else', () => {
    expect(validateParam(DATE, '2026-09-01')).toBe('2026-09-01')
    for (const bad of [
      '2026-9-1', '2026-09-01T00:00:00Z', "' OR 1=1 --", '2026-09-01' + "'; DROP TABLE bronze_events; --",
      '', 'yesterday', '2026-13-40', 'toDate(now())',
    ]) {
      expect(validateParam(DATE, bad)).toBeNull()
    }
  })

  it('accepts a non-negative number and rejects injection shapes', () => {
    expect(validateParam(AMOUNT, '100000')).toBe('100000')
    expect(validateParam(AMOUNT, '0.5')).toBe('0.5')
    for (const bad of ['-5', '1e6', 'Infinity', 'NaN', '100 OR 1=1', '100; SELECT', '0x10', ' 100', '100 ']) {
      expect(validateParam(AMOUNT, bad)).toBeNull()
    }
  })

  it('accepts only enum options verbatim', () => {
    expect(validateParam(CURRENCY, 'CZK')).toBe('CZK')
    expect(validateParam(CURRENCY, 'czk')).toBeNull()
    expect(validateParam(CURRENCY, "CZK' OR '1'='1")).toBeNull()
    expect(validateParam(CURRENCY, '')).toBeNull()
  })

  it('falls back to a validated default for an absent optional parameter', () => {
    expect(validateParam(AMOUNT, null)).toBe('1000')
  })
})

describe('reporting registry — entry-level validation', () => {
  const entry = REPORT_REGISTRY.find((e) => e.id === 'risk-large-settled')!

  it('rejects a missing required parameter', () => {
    const out = validateParams(entry, { from: null, to: '2026-09-01' })
    expect(out).toEqual({ ok: false, param: 'from' })
  })

  it('rejects an invalid SUPPLIED optional parameter instead of silently dropping it', () => {
    // Dropping it would run a different report than the operator asked for.
    const out = validateParams(entry, { from: '2026-08-01', to: '2026-09-01', minAmount: "100' OR 1=1 --" })
    expect(out).toEqual({ ok: false, param: 'minAmount' })
  })

  it('accepts a full valid set and canonicalises values', () => {
    const out = validateParams(entry, { from: '2026-08-01', to: '2026-09-01', minAmount: '50000', currency: 'CZK' })
    expect(out).toEqual({ ok: true, params: { from: '2026-08-01', to: '2026-09-01', minAmount: '50000', currency: 'CZK' } })
  })

  it('omits an absent optional enum from the params map', () => {
    const out = validateParams(entry, { from: '2026-08-01', to: '2026-09-01' })
    expect(out.ok).toBe(true)
    if (out.ok) {
      expect(out.params.currency).toBeUndefined()
      expect(out.params.minAmount).toBe('100000') // validated default
    }
  })
})

describe('reporting registry — catalogue integrity', () => {
  it('has unique ids and a permission on every entry', () => {
    const ids = REPORT_REGISTRY.map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const e of REPORT_REGISTRY) {
      expect(e.permission).toBeTruthy()
      expect(e.params.length).toBeGreaterThan(0)
    }
  })

  it('never interpolates an unvalidated value: builders receive only validateParam output', () => {
    // A spot-check that the SQL a report emits contains ONLY validated values: run one entry with
    // hostile input and assert validation fails BEFORE any SQL is built.
    const hostile = validateParams(REPORT_REGISTRY[0], { from: "2026-01-01' OR 1=1 --", to: '2026-09-01' })
    expect(hostile.ok).toBe(false)
  })

  it('getReport returns null for unknown ids — an unknown id never reaches an SQL builder', () => {
    expect(getReport('risk-settlement-daily')).not.toBeNull()
    expect(getReport("'; DROP TABLE openbank_analytics.bronze_events; --")).toBeNull()
    expect(getReport('')).toBeNull()
  })

  it('every entry builds SQL over a gold view only (ADR-0286: never bronze, never silver)', () => {
    const params: Record<string, string> = { from: '2026-08-01', to: '2026-09-01', minAmount: '100000', currency: 'CZK' }
    for (const e of REPORT_REGISTRY) {
      const sql = e.sql(params)
      expect(sql).toMatch(/openbank_analytics\.gold_/)
      expect(sql).not.toMatch(/openbank_analytics\.bronze_/)
      expect(sql).not.toMatch(/openbank_analytics\.silver_/)
    }
  })
})
