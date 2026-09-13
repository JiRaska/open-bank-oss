// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export const INCIDENT_SEVERITIES = ['P1_CRITICAL', 'P2_HIGH', 'P3_MEDIUM', 'P4_LOW'] as const
export const INCIDENT_STATUSES = ['OPEN', 'INVESTIGATING', 'CONTAINED', 'RESOLVED', 'CLOSED'] as const
export const INCIDENT_CATEGORIES = [
  'AVAILABILITY', 'INTEGRITY', 'CONFIDENTIALITY', 'AUTHENTICITY', 'UNAUTHORIZED_ACCESS',
  'DATA_BREACH', 'RANSOMWARE', 'DDOS', 'INSIDER_THREAT', 'SUPPLY_CHAIN', 'OTHER',
] as const

export type IncidentSeverity = (typeof INCIDENT_SEVERITIES)[number]
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number]
export type IncidentCategory = (typeof INCIDENT_CATEGORIES)[number]

export interface IctIncident {
  id: string
  title: string
  description: string
  category: IncidentCategory
  severity: IncidentSeverity
  status: IncidentStatus
  affectedServices: string[]
  detectedAt: string
  reportedAt: string
  containedAt: string | null
  resolvedAt: string | null
  rtoMinutes: number | null
  rpoMinutes: number | null
  reportedToRegulator: boolean
  regulatoryReportId: string | null
  assignedTo: string | null
  createdAt: string
  updatedAt: string
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
export const INCIDENT_REGISTER_LIMIT = 500
const MAX_AFFECTED_SERVICES = 100
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const requiredString = (value: unknown, maxLength = 200): string | null => {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed.length > 0 && trimmed.length <= maxLength ? trimmed : null
}
const nullableString = (value: unknown, maxLength = 200): string | null | undefined =>
  value === null ? null : requiredString(value, maxLength) ?? undefined
const instant = (value: unknown): string | null => {
  const candidate = requiredString(value)
  return candidate && RFC3339.test(candidate) && !Number.isNaN(Date.parse(candidate)) ? candidate : null
}
const nullableInstant = (value: unknown): string | null | undefined =>
  value === null ? null : instant(value) ?? undefined
const nullableMinutes = (value: unknown): number | null | undefined =>
  value === null ? null : Number.isSafeInteger(value) && (value as number) >= 0 ? value as number : undefined

export function parseIncident(raw: unknown): IctIncident | null {
  if (!isRecord(raw)) return null
  const id = requiredString(raw.id)
  const title = requiredString(raw.title)
  const description = requiredString(raw.description, 5_000)
  const category = requiredString(raw.category)
  const severity = requiredString(raw.severity)
  const status = requiredString(raw.status)
  const detectedAt = instant(raw.detectedAt)
  const reportedAt = instant(raw.reportedAt)
  const createdAt = instant(raw.createdAt)
  const updatedAt = instant(raw.updatedAt)
  const containedAt = nullableInstant(raw.containedAt)
  const resolvedAt = nullableInstant(raw.resolvedAt)
  const rtoMinutes = nullableMinutes(raw.rtoMinutes)
  const rpoMinutes = nullableMinutes(raw.rpoMinutes)
  const regulatoryReportId = nullableString(raw.regulatoryReportId, 120)
  const assignedTo = nullableString(raw.assignedTo)
  const affectedServices = Array.isArray(raw.affectedServices)
    ? raw.affectedServices.length <= MAX_AFFECTED_SERVICES
      ? raw.affectedServices.map(service => requiredString(service, 120))
      : null
    : null

  if (!id || !UUID.test(id) || !title || !description || !detectedAt || !reportedAt || !createdAt || !updatedAt) return null
  if (!category || !(INCIDENT_CATEGORIES as readonly string[]).includes(category)) return null
  if (!severity || !(INCIDENT_SEVERITIES as readonly string[]).includes(severity)) return null
  if (!status || !(INCIDENT_STATUSES as readonly string[]).includes(status)) return null
  if (!affectedServices || affectedServices.some(service => service === null) ||
    new Set(affectedServices).size !== affectedServices.length) return null
  if (containedAt === undefined || resolvedAt === undefined || rtoMinutes === undefined || rpoMinutes === undefined) return null
  if (regulatoryReportId === undefined || assignedTo === undefined || typeof raw.reportedToRegulator !== 'boolean') return null
  if (raw.reportedToRegulator !== (regulatoryReportId !== null)) return null

  return {
    id,
    title,
    description,
    category: category as IncidentCategory,
    severity: severity as IncidentSeverity,
    status: status as IncidentStatus,
    affectedServices: affectedServices as string[],
    detectedAt,
    reportedAt,
    containedAt,
    resolvedAt,
    rtoMinutes,
    rpoMinutes,
    reportedToRegulator: raw.reportedToRegulator,
    regulatoryReportId,
    assignedTo,
    createdAt,
    updatedAt,
  }
}

/** Reject the complete register when any row is not authoritative incident evidence. */
export function parseIncidentList(raw: unknown): IctIncident[] | null {
  if (!Array.isArray(raw) || raw.length > INCIDENT_REGISTER_LIMIT) return null
  const incidents = raw.map(parseIncident)
  if (incidents.some(incident => incident === null)) return null
  const verified = incidents as IctIncident[]
  return new Set(verified.map(incident => incident.id)).size === verified.length ? verified : null
}
