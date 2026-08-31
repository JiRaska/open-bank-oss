import { describe, expect, it } from 'vitest'
import { fxTrendChange, normaliseFxTrend } from '@/lib/fx/trend'

describe('FX trend normalisation', () => {
  it('sorts chronologically, deduplicates snapshots and rejects invalid prices', () => {
    expect(normaliseFxTrend([
      { validFrom: '2026-03-01T00:00:00Z', midRate: '25.2' },
      { timestamp: 'bad', rate: 999 },
      { validFrom: '2026-01-01T00:00:00Z', midRate: '24.8' },
      { validFrom: '2026-03-01T00:00:00Z', midRate: '25.2' },
      { validFrom: '2026-02-01T00:00:00Z', midRate: '0' },
    ])).toEqual([
      { timestamp: '2026-01-01T00:00:00Z', rate: 24.8 },
      { timestamp: '2026-03-01T00:00:00Z', rate: 25.2 },
    ])
  })

  it('computes change from the oldest to newest valid point', () => {
    expect(fxTrendChange([{ timestamp: 'a', rate: 25 }, { timestamp: 'b', rate: 26 }])).toBe(4)
    expect(fxTrendChange([{ timestamp: 'a', rate: 25 }])).toBeNull()
  })

  it('keeps only the latest fixing for each UTC business day', () => {
    expect(normaliseFxTrend([
      { timestamp: '2026-08-31T08:00:00Z', rate: 25.1 },
      { timestamp: '2026-08-31T14:30:00Z', rate: 25.2 },
      { timestamp: '2026-05-31T12:00:00Z', rate: 25 },
    ])).toEqual([
      { timestamp: '2026-05-31T12:00:00Z', rate: 25 },
      { timestamp: '2026-08-31T14:30:00Z', rate: 25.2 },
    ])
  })
})
