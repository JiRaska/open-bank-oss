// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/onboarding/analytics/page.tsx'), 'utf8')
const css = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('onboarding analytics semantic experience', () => {
  it('uses adaptive SVG chart tokens and named chart descriptions', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const token of ['accent', 'success', 'success-text', 'warning', 'danger', 'danger-text', 'chart-purple', 'chart-cyan', 'text-tertiary']) {
      expect(source).toContain(`var(--${token})`)
    }
    expect(source).toContain('role="img"')
    expect(source).toContain('Chart of viewed and completed sessions')
  })

  it('has explicit desktop, tablet and mobile information layouts', () => {
    expect(css).toContain('.onboarding-analytics-kpis')
    expect(css).toContain('@media (max-width: 900px)')
    expect(css).toContain('@media (max-width: 640px)')
    expect(css).toContain('.onboarding-analytics-dwell')
    expect(css).toContain('.onboarding-analytics-failure-row')
  })
})
