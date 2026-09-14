// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  const Mark = ({ fill }: { fill?: string }) => fill ? <i data-fill={fill} /> : null
  const Nil = () => null
  return {
    ResponsiveContainer: Pass, BarChart: Pass, ScatterChart: Pass, PieChart: Pass, Pie: Pass,
    Bar: Pass, Scatter: Mark, Cell: Mark, XAxis: Nil, YAxis: Nil, ZAxis: Nil,
    CartesianGrid: Nil, Tooltip: Nil, Legend: Nil, ReferenceLine: Nil,
  }
})

import {
  AffordabilityScatter, BucketBars, OutcomeTrend, ReasonPareto, StageMixPie,
} from '@/components/lending/risk/charts'

afterEach(cleanup)

const decision = {
  applicationId: 'app-1', engineOutcome: 'APPROVE',
  affordability: { dsti: 0.2, dti: 2, dstiIncludingExistingDebt: 0.3 },
} as never

describe('lending risk charts', () => {
  it('renders every visual with an accessible meaning and adaptive semantic colours', () => {
    render(
      <LanguageProvider>
        <OutcomeTrend data={[{ week: '2026-W36', APPROVE: 2, REFER: 1, DECLINE: 1 }]} />
        <ReasonPareto data={[{ code: 'EXCLUSION_MATCHED', ruleId: 'rule-1', count: 1 }]} />
        <AffordabilityScatter decisions={[decision]} dstiLimit={0.45} dtiLimit={8} includeExistingDebt={false} />
        <StageMixPie stages={[
          { stage: 'STAGE_1', loans: 1, outstanding: 100 },
          { stage: 'STAGE_2', loans: 1, outstanding: 50 },
          { stage: 'STAGE_3', loans: 1, outstanding: 25 },
        ]} />
        <BucketBars buckets={[
          { bucket: 'CURRENT', count: 1, outstanding: 100 },
          { bucket: 'DPD_1_30', count: 1, outstanding: 50 },
          { bucket: 'DPD_31_60', count: 1, outstanding: 25 },
          { bucket: 'DPD_61_90', count: 1, outstanding: 10 },
          { bucket: 'DPD_90_PLUS', count: 1, outstanding: 5 },
        ]} />
      </LanguageProvider>,
    )

    expect(screen.getAllByRole('group')).toHaveLength(5)
    expect(screen.getByRole('group', { name: /weekly engine outcomes/i })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: /affordability against policy DSTI and DTI limits/i })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: /IFRS 9 stage/i })).toBeInTheDocument()

    const fills = Array.from(document.querySelectorAll('[data-fill]')).map(node => node.getAttribute('data-fill'))
    for (const colour of ['var(--success)', 'var(--warning)', 'var(--danger)', 'var(--accent)', 'var(--info)', 'var(--chart-purple)']) {
      expect(fills).toContain(colour)
    }
  })
})
