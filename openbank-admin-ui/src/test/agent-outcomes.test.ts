// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect } from 'vitest'
import {
  deriveAgentOutcomes,
  formatLatency,
  MIN_DECIDED_FOR_RATE,
  PROPOSAL_PAGE_CAP,
  type ProposalOutcomeInput,
} from '@/lib/governance/agentOutcomes'

const HOUR = 3600_000

function proposal(state: string, proposedAt: string, decidedAt: string | null = null): ProposalOutcomeInput {
  return { state, proposedAt, decidedAt }
}

/** n decided proposals, each taking `hours` to decide, `approvedCount` of them approved. */
function decidedSet(approvedCount: number, rejectedCount: number, hours = 1): ProposalOutcomeInput[] {
  const base = Date.UTC(2026, 5, 10, 12, 0, 0)
  const rows: ProposalOutcomeInput[] = []
  for (let i = 0; i < approvedCount + rejectedCount; i++) {
    const from = base + i * HOUR
    rows.push(proposal(
      i < approvedCount ? 'APPROVED' : 'REJECTED',
      new Date(from).toISOString(),
      new Date(from + hours * HOUR).toISOString(),
    ))
  }
  return rows
}

describe('deriveAgentOutcomes — the denominator', () => {
  it('rates approvals over DECIDED proposals, never over all proposals', () => {
    // 6 approved, 2 rejected, 12 never decided. approved/total would read 30%.
    const rows = [...decidedSet(6, 2), ...Array.from({ length: 12 }, (_, i) =>
      proposal('PROPOSED', new Date(Date.UTC(2026, 6, 1, i)).toISOString()))]

    const m = deriveAgentOutcomes(rows)

    expect(m.total).toBe(20)
    expect(m.decided).toBe(8)
    expect(m.pending).toBe(12)
    expect(m.approvalRate).toBeCloseTo(6 / 8, 10)
    expect(m.approvalRate).not.toBeCloseTo(6 / 20, 3)
  })

  it('reports insufficient data rather than a misleading 100% on a thin sample', () => {
    const m = deriveAgentOutcomes(decidedSet(MIN_DECIDED_FOR_RATE - 1, 0))

    expect(m.decided).toBe(MIN_DECIDED_FOR_RATE - 1)
    expect(m.insufficientData).toBe(true)
    expect(m.approvalRate).toBeNull()
    expect(m.latencyP50Seconds).toBeNull()
    expect(m.latencyP95Seconds).toBeNull()
  })

  it('reports a rate at exactly the threshold', () => {
    const m = deriveAgentOutcomes(decidedSet(MIN_DECIDED_FOR_RATE, 0))

    expect(m.insufficientData).toBe(false)
    expect(m.approvalRate).toBe(1)
  })

  it('has no proposals to measure and says so without dividing by zero', () => {
    const m = deriveAgentOutcomes([])

    expect(m).toMatchObject({ total: 0, decided: 0, pending: 0, approvalRate: null, latencySamples: 0 })
    expect(m.insufficientData).toBe(true)
  })
})

describe('deriveAgentOutcomes — latency and its own, different denominator', () => {
  it('computes p50 and p95 by nearest rank, so each figure is a latency some proposal had', () => {
    const base = Date.UTC(2026, 5, 10, 12)
    // Latencies 1..10 hours.
    const rows = Array.from({ length: 10 }, (_, i) => proposal(
      'APPROVED',
      new Date(base).toISOString(),
      new Date(base + (i + 1) * HOUR).toISOString(),
    ))

    const m = deriveAgentOutcomes(rows)

    expect(m.latencySamples).toBe(10)
    expect(m.latencyP50Seconds).toBe(5 * 3600)
    expect(m.latencyP95Seconds).toBe(10 * 3600)
  })

  it('EXCLUDES an epoch-default timestamp instead of reporting a 56-year review', () => {
    // `Instant.EPOCH` as a data-class default is a lie that isNotNull() agrees with (#3882);
    // folded into the sample it would drag p95 to decades and read as a real outlier.
    const rows = [
      ...decidedSet(5, 0, 2),
      proposal('APPROVED', '1970-01-01T00:00:00Z', '2026-06-10T12:00:00Z'),
    ]

    const m = deriveAgentOutcomes(rows)

    expect(m.decided).toBe(6)
    expect(m.latencySamples).toBe(5)
    expect(m.latencyExcluded).toBe(1)
    expect(m.latencyP95Seconds).toBe(2 * 3600)
    // The excluded row still counts toward the acceptance rate — it IS a decision.
    expect(m.approvalRate).toBe(1)
  })

  it('excludes a negative and an unparseable duration rather than counting them as zero', () => {
    const rows = [
      ...decidedSet(5, 0, 3),
      proposal('APPROVED', '2026-06-10T12:00:00Z', '2026-06-10T11:00:00Z'),
      proposal('REJECTED', '2026-06-10T12:00:00Z', 'not-a-date'),
      proposal('REJECTED', '2026-06-10T12:00:00Z', null),
    ]

    const m = deriveAgentOutcomes(rows)

    expect(m.decided).toBe(8)
    expect(m.latencySamples).toBe(5)
    expect(m.latencyExcluded).toBe(3)
    expect(m.latencyP50Seconds).toBe(3 * 3600)
  })

  it('reports the last decision, which is how a fed-but-unreviewed queue becomes visible', () => {
    const rows = [
      ...decidedSet(5, 0),
      proposal('PROPOSED', '2026-08-07T20:30:00Z'),
    ]

    const m = deriveAgentOutcomes(rows)

    expect(m.pending).toBe(1)
    expect(m.lastDecisionAt).not.toBeNull()
    // Recency, not non-nullity: the decision must be the newest decided row's time.
    expect(Date.parse(m.lastDecisionAt as string)).toBe(Date.UTC(2026, 5, 10, 12 + 4 + 1))
  })
})

describe('deriveAgentOutcomes — truncation', () => {
  it('flags a page-capped list, because the figures then describe a window and not a history', () => {
    const uncapped = deriveAgentOutcomes(decidedSet(PROPOSAL_PAGE_CAP - 1, 0))
    const capped = deriveAgentOutcomes(decidedSet(PROPOSAL_PAGE_CAP, 0))

    expect(uncapped.truncated).toBe(false)
    expect(capped.truncated).toBe(true)
  })
})

describe('formatLatency', () => {
  it('renders each magnitude in a unit a reviewer reads without dividing', () => {
    expect(formatLatency(45)).toBe('45 s')
    expect(formatLatency(90)).toBe('1 m')
    expect(formatLatency(3600)).toBe('1 h')
    expect(formatLatency(3900)).toBe('1 h 5 m')
    expect(formatLatency(86400)).toBe('1 d')
    expect(formatLatency(217336)).toBe('2 d 12 h')
  })
})
