import { describe, expect, it } from 'vitest'
import { fxTrendDirection, fxTrendSummary, fxTrendTimelinePositions } from '@/lib/fx/trend'

describe('fxTrendSummary', () => {
  it('describes the chronological endpoints and observed range', () => {
    const summary = fxTrendSummary([
      { timestamp: '2026-06-01T00:00:00Z', rate: 24.5 },
      { timestamp: '2026-07-01T00:00:00Z', rate: 24.1 },
      { timestamp: '2026-08-31T00:00:00Z', rate: 24.99 },
    ])
    expect(summary).toMatchObject({ first: { rate: 24.5 }, last: { rate: 24.99 }, minimum: { rate: 24.1 }, maximum: { rate: 24.99 } })
    expect(summary?.changePercent).toBeCloseTo(2)
  })

  it('does not fabricate a summary from fewer than two fixings', () => {
    expect(fxTrendSummary([{ timestamp: '2026-08-31T00:00:00Z', rate: 24.99 }])).toBeNull()
  })

  it('treats an unchanged rate as neutral rather than growth', () => {
    expect(fxTrendDirection(0)).toBe('flat')
    expect(fxTrendDirection(0.01)).toBe('up')
    expect(fxTrendDirection(-0.01)).toBe('down')
  })

  it('places observations according to elapsed time', () => {
    expect(fxTrendTimelinePositions([
      { timestamp: '2026-06-01T00:00:00Z', rate: 24.5 },
      { timestamp: '2026-06-10T00:00:00Z', rate: 24.6 },
      { timestamp: '2026-07-01T00:00:00Z', rate: 24.7 },
    ])).toEqual([0, 0.3, 1])
  })
})
