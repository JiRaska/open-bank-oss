export interface FxTrendPoint { timestamp: string; rate: number }

export interface FxTrendSummary {
  first: FxTrendPoint
  last: FxTrendPoint
  minimum: FxTrendPoint
  maximum: FxTrendPoint
  changePercent: number
}

export type FxTrendDirection = 'up' | 'down' | 'flat'

export function fxTrendDirection(changePercent: number): FxTrendDirection {
  if (changePercent > 0) return 'up'
  if (changePercent < 0) return 'down'
  return 'flat'
}

/** Position fixings on the real elapsed-time axis instead of spacing sparse dates evenly. */
export function fxTrendTimelinePositions(points: FxTrendPoint[]): number[] {
  if (points.length < 2) return points.map(() => 0)
  const start = Date.parse(points[0].timestamp)
  const end = Date.parse(points.at(-1)!.timestamp)
  const span = end - start
  if (!Number.isFinite(span) || span <= 0) return points.map((_, index) => index / (points.length - 1))
  return points.map(point => (Date.parse(point.timestamp) - start) / span)
}

/** Normalise a newest/oldest/mixed API response into one latest valid fixing per UTC day, oldest first. */
export function normaliseFxTrend(rows: unknown[]): FxTrendPoint[] {
  const latestByDate = new Map<string, FxTrendPoint>()
  for (const value of rows) {
    if (!value || typeof value !== 'object') continue
    const row = value as Record<string, unknown>
    const timestamp = typeof row.timestamp === 'string' ? row.timestamp :
      typeof row.validFrom === 'string' ? row.validFrom : ''
    const rawRate = row.rate ?? row.midRate
    const rate = typeof rawRate === 'number' ? rawRate : Number(rawRate)
    if (!timestamp || !Number.isFinite(Date.parse(timestamp)) || !Number.isFinite(rate) || rate <= 0) continue
    const date = new Date(timestamp).toISOString().slice(0, 10)
    const previous = latestByDate.get(date)
    if (!previous || Date.parse(timestamp) > Date.parse(previous.timestamp)) {
      latestByDate.set(date, { timestamp, rate })
    }
  }
  return [...latestByDate.values()].sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp))
}

export function fxTrendChange(points: FxTrendPoint[]): number | null {
  if (points.length < 2 || points[0].rate === 0) return null
  return ((points.at(-1)!.rate - points[0].rate) / points[0].rate) * 100
}

export function fxTrendSummary(points: FxTrendPoint[]): FxTrendSummary | null {
  const changePercent = fxTrendChange(points)
  if (changePercent === null) return null
  return {
    first: points[0],
    last: points.at(-1)!,
    minimum: points.reduce((minimum, point) => point.rate < minimum.rate ? point : minimum),
    maximum: points.reduce((maximum, point) => point.rate > maximum.rate ? point : maximum),
    changePercent,
  }
}
