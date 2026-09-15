// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/feedback/page.tsx'), 'utf8')
const chart = readFileSync(path.resolve(__dirname, '../components/feedback/ScreenFeedbackChart.tsx'), 'utf8')

describe('screen feedback chart performance and theme seam', () => {
  it('loads Recharts only after feedback data is available', () => {
    expect(page).not.toContain("from 'recharts'")
    expect(page).toContain("import('@/components/feedback/ScreenFeedbackChart').then(module => module.ScreenFeedbackChart)")
    expect(page).toContain('ssr: false')
  })

  it('keeps chart meaning legible across themes', () => {
    expect(chart).toContain('fill="var(--danger)"')
    expect(chart).toContain('fill="var(--accent)"')
    expect(chart).toContain('fill="var(--warning)"')
    expect(chart).toContain('accessibilityLayer')
  })
})
