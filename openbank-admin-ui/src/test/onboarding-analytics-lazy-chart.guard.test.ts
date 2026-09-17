// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/onboarding/analytics/page.tsx'), 'utf8')
const charts = readFileSync(path.resolve(__dirname, '../components/onboarding/OnboardingAnalyticsCharts.tsx'), 'utf8')

describe('onboarding analytics chart loading boundary', () => {
  it('keeps Recharts out of loading, denial and recovery bundles', () => {
    expect(page).toContain("dynamic(")
    expect(page).toContain("import('@/components/onboarding/OnboardingAnalyticsCharts')")
    expect(page).not.toContain("from 'recharts'")
    expect(charts).toContain("from 'recharts'")
  })

  it('reserves panel space and exposes the chart data to assistive technology', () => {
    expect(page).toContain('loading: () => <div style={{ minHeight: 600 }}')
    expect(charts.match(/role="img"/g)).toHaveLength(3)
    expect(charts).toContain('funnelSummary')
    expect(charts).toContain('rateSummary')
    expect(charts).toContain('kycSummary')
  })
})
