// SPDX-License-Identifier: Apache-2.0

export interface PortfolioSummary {
  count: number
  lowerBound: boolean
  statuses: string[]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseRows(raw: unknown): Record<string, unknown>[] {
  if (!Array.isArray(raw) || raw.some(row => !isRecord(row))) throw new Error('Invalid portfolio rows')
  return raw as Record<string, unknown>[]
}

function summarize(rows: Record<string, unknown>[], lowerBound: boolean): PortfolioSummary {
  const statuses = [...new Set(rows.map(row => {
    const value = row.status ?? row.state
    if (value === undefined || value === null || value === '') return null
    if (typeof value !== 'string') throw new Error('Invalid portfolio status')
    return value
  }).filter((value): value is string => value !== null))]
  return { count: rows.length, lowerBound, statuses }
}

export function parseAccountPortfolio(raw: unknown): PortfolioSummary {
  if (!isRecord(raw) || !isRecord(raw.pagination) || typeof raw.pagination.hasMore !== 'boolean') {
    throw new Error('Invalid account portfolio')
  }
  const cursor = raw.pagination.nextCursor
  if (cursor !== null && typeof cursor !== 'string') throw new Error('Invalid account cursor')
  return summarize(parseRows(raw.data), raw.pagination.hasMore)
}

export function parseLendingPortfolio(raw: unknown): PortfolioSummary {
  return summarize(parseRows(raw), false)
}

export function parseAmlPortfolio(raw: unknown, limit: number): PortfolioSummary {
  const rows = parseRows(raw)
  if (rows.length > limit) throw new Error('Invalid AML portfolio window')
  return summarize(rows, rows.length === limit)
}
