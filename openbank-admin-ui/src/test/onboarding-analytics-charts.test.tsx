// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { OnboardingAnalyticsCharts } from '@/components/onboarding/OnboardingAnalyticsCharts'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  BarChart: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Bar: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  XAxis: () => null,
  YAxis: () => null,
  CartesianGrid: () => null,
  Tooltip: () => null,
  Cell: () => null,
  LabelList: () => null,
  LineChart: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Line: () => null,
  PieChart: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Pie: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Legend: () => null,
}))

describe('onboarding analytics chart semantics', () => {
  it('exposes the same evidence conveyed visually as English accessible summaries', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <OnboardingAnalyticsCharts
          funnel={[{ step: 'WELCOME', stepOrdinal: 1, label: 'Welcome', viewed: 40, completed: 30, holdAbandons: 0, dropOffPct: 25, medianSeconds: 12 }]}
          rate={[{ day: '08-31', rate: 90 }]}
          kyc={[{ name: 'BANK_ID', value: 30 }]}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('img', { name: /Welcome: 40 viewed, 30 completed, 25\.0 % drop-off/ })).toBeVisible()
    expect(screen.getByRole('img', { name: /08-31: 90\.0 %/ })).toBeVisible()
    expect(screen.getByRole('img', { name: /BANK_ID: 30/ })).toBeVisible()
  })
})
