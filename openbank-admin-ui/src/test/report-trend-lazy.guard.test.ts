// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/reporting/page.tsx'), 'utf8')

describe('report trend performance seam', () => {
  it('keeps Recharts out of the reporting entry bundle until a result needs a trend', () => {
    expect(page).not.toContain("import { ReportTrend } from '@/components/reporting/ReportTrend'")
    expect(page).toContain("import('@/components/reporting/ReportTrend').then(module => module.ReportTrend)")
    expect(page).toContain('ssr: false')
  })
})
