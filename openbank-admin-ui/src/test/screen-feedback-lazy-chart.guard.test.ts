// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/feedback/page.tsx'), 'utf8')
const chart = readFileSync(path.resolve(__dirname, '../components/feedback/ScreenPainChart.tsx'), 'utf8')

describe('screen feedback chart loading boundary', () => {
  it('keeps Recharts out of the feedback board critical bundle', () => {
    expect(page).toContain("dynamic(")
    expect(page).toContain("import('@/components/feedback/ScreenPainChart')")
    expect(page).not.toContain("from 'recharts'")
    expect(chart).toContain("from 'recharts'")
  })

  it('reserves the chart height and gives the visualisation an accessible name', () => {
    expect(page).toContain('loading: () => <div style={{ height: 320 }}')
    expect(chart).toContain('role="img"')
    expect(chart).toContain('Bug, idea and confusion reports by screen')
    expect(chart).toContain('`${row.screen}: ${bug} ${row.bug}, ${idea} ${row.idea}, ${confusing} ${row.confusing}`')
  })
})
