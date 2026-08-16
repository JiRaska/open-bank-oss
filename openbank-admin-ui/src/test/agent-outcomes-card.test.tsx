// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// The panel's contract is not "renders a number" — it is "never renders a rate without
// the denominator that rate is over" (#4462). These tests assert the coverage line,
// because that line is the whole reason the figure is publishable.

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { OutcomeMetricsCard } from '@/components/agent/AgentOutcomes'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { ProposalOutcomeInput } from '@/lib/governance/agentOutcomes'

const HOUR = 3600_000

function decided(approved: number, rejected: number, hours = 2): ProposalOutcomeInput[] {
  const base = Date.UTC(2026, 5, 10, 12)
  return Array.from({ length: approved + rejected }, (_, i) => ({
    state: i < approved ? 'APPROVED' : 'REJECTED',
    proposedAt: new Date(base + i * HOUR).toISOString(),
    decidedAt: new Date(base + i * HOUR + hours * HOUR).toISOString(),
  }))
}

function pending(n: number): ProposalOutcomeInput[] {
  return Array.from({ length: n }, (_, i) => ({
    state: 'PROPOSED',
    proposedAt: new Date(Date.UTC(2026, 7, 1, i)).toISOString(),
    decidedAt: null,
  }))
}

/** The panel reads its labels from the language context; the provider defaults to English. */
function renderCard(ui: React.ReactElement) {
  return render(<LanguageProvider>{ui}</LanguageProvider>)
}

describe('OutcomeMetricsCard', () => {
  it('renders the rate WITH its denominator and the pending proposals it excludes', () => {
    renderCard(<OutcomeMetricsCard items={[...decided(6, 2), ...pending(12)]} />)

    expect(screen.getByText('75%')).toBeTruthy()
    expect(screen.getByText('6 of 8 decided')).toBeTruthy()
    expect(screen.getByText(/Coverage: 8 of 20 proposals decided \(12 still pending/)).toBeTruthy()
  })

  it('shows insufficient data instead of a 100% built on two decisions', () => {
    renderCard(<OutcomeMetricsCard items={decided(2, 0)} />)

    expect(screen.queryByText('100%')).toBeNull()
    expect(screen.getAllByText('insufficient data').length).toBeGreaterThan(0)
    expect(screen.getByText('2 decided, needs 5')).toBeTruthy()
  })

  it('states the latency sample separately, since it is a different denominator', () => {
    const rows = [
      ...decided(5, 0, 2),
      { state: 'APPROVED', proposedAt: '1970-01-01T00:00:00Z', decidedAt: '2026-06-10T12:00:00Z' },
    ]

    renderCard(<OutcomeMetricsCard items={rows} />)

    expect(screen.getByText(/Latency measured over 5 of 6 decided; 1 excluded for unusable timestamps/)).toBeTruthy()
    expect(screen.getAllByText('5 samples').length).toBe(2)
    // p50 and p95 are both 2 h here — the point is that neither absorbed the epoch row.
    expect(screen.getAllByText('2 h').length).toBe(2)
  })

  it('has nothing to measure and says so rather than rendering a zero', () => {
    renderCard(<OutcomeMetricsCard items={[]} />)

    expect(screen.getByText('No proposals — nothing to measure.')).toBeTruthy()
    expect(screen.queryByText(/Coverage:/)).toBeNull()
    expect(screen.queryByText('0%')).toBeNull()
  })

  it('renders a weekly rate once a week has enough decisions, and "insufficient data" when it does not', () => {
    // Week of 2026-06-08: 5 decided, 3 approved -> a real rate.
    renderCard(<OutcomeMetricsCard items={decided(3, 2)} />)

    expect(screen.getByText('2026-06-08')).toBeTruthy()
    expect(screen.getByText('60% (3 of 5)')).toBeTruthy()
  })

  it('shows the weekly insufficient-data label with its own count, not the overall one', () => {
    // 2 decided this week; the overall coverage line and the weekly line are different claims.
    renderCard(<OutcomeMetricsCard items={decided(2, 0)} />)

    expect(screen.getByText('insufficient data (2 decided)')).toBeTruthy()
  })
})
