// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parseAmlCaseSnapshot } from '@/lib/aml/cases'

const pageSource = readFileSync(path.resolve(__dirname, '../app/aml/page.tsx'), 'utf8')

const validCase = {
  id: 'case-1',
  customerName: 'Example Customer',
  customerType: 'PERSON',
  riskLevel: 'HIGH',
  status: 'PENDING_REVIEW',
  score: 82,
  timestamp: '2026-09-09T12:00:00Z',
}

describe('AML case evidence boundary', () => {
  it('accepts the supported list and envelope response shapes', () => {
    expect(parseAmlCaseSnapshot([validCase])).toEqual({ cases: [validCase], excludedCount: 0 })
    expect(parseAmlCaseSnapshot({ cases: [validCase] })).toEqual({ cases: [validCase], excludedCount: 0 })
  })

  it('retains valid cases and counts malformed evidence', () => {
    const result = parseAmlCaseSnapshot({ cases: [
      validCase,
      { ...validCase, id: '' },
      { ...validCase, score: 101 },
      { ...validCase, status: null },
      { ...validCase, timestamp: 'not-a-date' },
    ] })

    expect(result).toEqual({ cases: [validCase], excludedCount: 4 })
  })

  it('does not invent rows for an invalid response envelope', () => {
    expect(parseAmlCaseSnapshot({ cases: 'invalid' })).toEqual({ cases: [], excludedCount: 0 })
    expect(parseAmlCaseSnapshot(null)).toEqual({ cases: [], excludedCount: 0 })
  })

  it('discloses exclusions and uses the shared danger semantic', () => {
    expect(pageSource).toContain('excludedCount > 0')
    expect(pageSource).toContain('displayed totals use validated records only.')
    expect(pageSource).toContain("color: 'var(--warning-text)'")
    expect(pageSource).not.toContain('#dc2626')
  })
})
