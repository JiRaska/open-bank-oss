export interface FxTrendPoint { timestamp: string; rate: number }

/** Normalise a newest/oldest/mixed API response into one valid point per instant, oldest first. */
export function normaliseFxTrend(rows: unknown[]): FxTrendPoint[] {
  const unique = new Map<string, FxTrendPoint>()
  for (const value of rows) {
    if (!value || typeof value !== 'object') continue
    const row = value as Record<string, unknown>
    const timestamp = typeof row.timestamp === 'string' ? row.timestamp :
      typeof row.validFrom === 'string' ? row.validFrom : ''
    const rawRate = row.rate ?? row.midRate
    const rate = typeof rawRate === 'number' ? rawRate : Number(rawRate)
    if (!timestamp || !Number.isFinite(Date.parse(timestamp)) || !Number.isFinite(rate) || rate <= 0) continue
    unique.set(timestamp, { timestamp, rate })
  }
  return [...unique.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
}

export function fxTrendChange(points: FxTrendPoint[]): number | null {
  if (points.length < 2 || points[0].rate === 0) return null
  return ((points.at(-1)!.rate - points[0].rate) / points[0].rate) * 100
}
