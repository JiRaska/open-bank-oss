// SPDX-License-Identifier: Apache-2.0

export type IncidentImpact = {
  affectedByType: Record<string, number>
  total: number
  drilldownAvailable: false
  projectionStatus: 'MISSING' | 'PARTIAL' | 'AVAILABLE' | 'UNKNOWN'
}

/** Copy only the aggregate contract. A legacy response cannot establish completeness. */
export function parseIncidentImpact(value: unknown): IncidentImpact {
  if (!value || typeof value !== 'object') throw new Error('Invalid impact')
  const body = value as Record<string, unknown>
  if (!body.affectedByType || typeof body.affectedByType !== 'object' || Array.isArray(body.affectedByType)) {
    throw new Error('Invalid impact types')
  }
  const entries = Object.entries(body.affectedByType)
  if (entries.length > 50 || !entries.every(([type, count]) =>
    /^[A-Z][A-Z0-9_]{0,79}$/.test(type) && Number.isSafeInteger(count) && Number(count) >= 0)) {
    throw new Error('Invalid impact counts')
  }
  const total = entries.reduce((sum, [, count]) => sum + Number(count), 0)
  const projectionStatus = body.projectionStatus === undefined ? 'UNKNOWN' : body.projectionStatus
  if (total > 200 || total !== body.total || body.drilldownAvailable !== false ||
      typeof projectionStatus !== 'string' || !['MISSING', 'PARTIAL', 'AVAILABLE', 'UNKNOWN'].includes(projectionStatus) ||
      (projectionStatus === 'MISSING' && (total !== 0 || entries.length !== 0))) {
    throw new Error('Inconsistent impact')
  }
  return {
    affectedByType: Object.fromEntries(entries) as Record<string, number>,
    total, drilldownAvailable: false,
    projectionStatus: projectionStatus as IncidentImpact['projectionStatus'],
  }
}
