// SPDX-License-Identifier: Apache-2.0

export const INCIDENT_SEVERITIES = ['P1_CRITICAL', 'P2_HIGH', 'P3_MEDIUM', 'P4_LOW'] as const
export const INCIDENT_STATUSES = ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED'] as const
export const INCIDENT_CATEGORIES = [
  'AVAILABILITY', 'INTEGRITY', 'CONFIDENTIALITY', 'AUTHENTICITY', 'UNAUTHORIZED_ACCESS',
  'DATA_BREACH', 'RANSOMWARE', 'DDOS', 'INSIDER_THREAT', 'SUPPLY_CHAIN', 'OTHER',
] as const

export type IctIncident = {
  id: string
  title: string
  severity: typeof INCIDENT_SEVERITIES[number]
  status: typeof INCIDENT_STATUSES[number]
  category: typeof INCIDENT_CATEGORIES[number]
  detectedAt: string
  affectedServices: string[]
  reportedToRegulator: boolean
}

export type IctIncidentEnvelope =
  | { available: true; incidents: IctIncident[] }
  | { available: false; reason: string }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const SERVICE = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}

function oneOf<const T extends readonly string[]>(value: unknown, allowed: T): T[number] {
  if (typeof value !== 'string' || !allowed.includes(value)) throw new Error('Unexpected enum value')
  return value as T[number]
}

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > max) throw new Error('Invalid text')
  return value
}

function incident(value: unknown): IctIncident {
  const item = record(value)
  if (typeof item.id !== 'string' || !UUID.test(item.id)) throw new Error('Invalid incident id')
  if (typeof item.detectedAt !== 'string' || !RFC3339.test(item.detectedAt) || !Number.isFinite(Date.parse(item.detectedAt))) {
    throw new Error('Invalid detection time')
  }
  if (!Array.isArray(item.affectedServices) || item.affectedServices.length > 100 ||
      item.affectedServices.some(service => typeof service !== 'string' || !SERVICE.test(service))) {
    throw new Error('Invalid affected services')
  }
  if (new Set(item.affectedServices).size !== item.affectedServices.length) throw new Error('Duplicate affected service')
  if (typeof item.reportedToRegulator !== 'boolean') throw new Error('Invalid regulator state')
  return {
    id: item.id,
    title: text(item.title, 2_000),
    category: oneOf(item.category, INCIDENT_CATEGORIES),
    severity: oneOf(item.severity, INCIDENT_SEVERITIES),
    status: oneOf(item.status, INCIDENT_STATUSES),
    detectedAt: item.detectedAt,
    affectedServices: item.affectedServices as string[],
    reportedToRegulator: item.reportedToRegulator,
  }
}

export function parseIctIncidentEnvelope(value: unknown): IctIncidentEnvelope {
  const envelope = record(value)
  if (envelope.available === false) return { available: false, reason: text(envelope.reason, 100) }
  if (envelope.available !== true || !Array.isArray(envelope.incidents) || envelope.incidents.length > 1_000) {
    throw new Error('Invalid incident envelope')
  }
  const incidents = envelope.incidents.map(incident)
  if (new Set(incidents.map(item => item.id)).size !== incidents.length) throw new Error('Duplicate incident id')
  return { available: true, incidents }
}

export type IctIncidentSummary = {
  open: number
  openCritical: number
  unreported: number
  score: number
  status: 'ok' | 'degraded' | 'critical'
}

/**
 * One scoring definition for the detailed register and the Security Excellence card.
 * RESOLVED is no longer operationally open even before the immutable record is CLOSED;
 * only the service's P1/P2 severities count as critical/high impact.
 */
export function summarizeIctIncidents(incidents: IctIncident[]): IctIncidentSummary {
  const openItems = incidents.filter(item => item.status !== 'RESOLVED' && item.status !== 'CLOSED')
  const openCritical = openItems.filter(item => item.severity === 'P1_CRITICAL' || item.severity === 'P2_HIGH').length
  const unreported = openItems.filter(item => !item.reportedToRegulator).length
  return {
    open: openItems.length,
    openCritical,
    unreported,
    score: Math.max(0, 100 - openCritical * 30 - (openItems.length - openCritical) * 10 - unreported * 5),
    status: openCritical > 0 ? 'critical' : openItems.length > 0 ? 'degraded' : 'ok',
  }
}
