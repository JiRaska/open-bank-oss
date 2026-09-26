// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/reporting/page.tsx'), 'utf8')

describe('reporting chart loading boundary', () => {
  it('keeps Recharts out of the initial catalogue and parameter workspace', () => {
    expect(page).not.toContain("import { ReportTrend } from '@/components/reporting/ReportTrend'")
    expect(page).toContain("import('@/components/reporting/ReportTrend')")
    expect(page).toContain('module => module.ReportTrend')
  })
})
