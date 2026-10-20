// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto'

export const READINESS_DIMENSION_CODES = ['C1', 'C2', 'C3', 'C4', 'C5', 'C6', 'C7', 'C8', 'C9'] as const
export type ReadinessDimensionCode = typeof READINESS_DIMENSION_CODES[number]

export interface ReadinessService {
  service: string
  money_path: boolean
  scores: Record<ReadinessDimensionCode, number>
  evidence: Record<ReadinessDimensionCode, string>
  gate: 'GO' | 'NO-GO' | 'NOT-DEPLOYED'
}

export interface ReadinessSnapshotReport {
  schema_version: 1
  generated_for: string
  collected_at: string
  source_sha: string
  scope: {
    kind: 'service-module-matrix-v1'
    service_count: number
    money_path_count: number
    roster_sha256: string
  }
  dimensions: { code: ReadinessDimensionCode; name: string }[]
  services: ReadinessService[]
}

const SHA1 = /^[0-9a-f]{40}$/
const SHA256 = /^[0-9a-f]{64}$/
const SERVICE_NAME = /^[a-z0-9][a-z0-9-]*$/
const ISO_UTC = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/
const MONEY_CRITICAL = new Set<ReadinessDimensionCode>(['C1', 'C5', 'C7'])
const SCORES = new Set([0, 1, 2, 3])

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function keysMatch(value: Record<string, unknown>, expected: readonly string[]): boolean {
  return expected.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => expected.includes(key))
}

function isCollectedAt(value: unknown, nowMs: number): value is string {
  if (typeof value !== 'string' || !ISO_UTC.test(value)) return false
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) && parsed <= nowMs + 5 * 60 * 1000
}

function isDateOnly(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d\d-\d\d$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

function rosterDigest(services: readonly ReadinessService[]): string {
  return createHash('sha256')
    .update(services.map(service => `${service.service}:${service.money_path}`).sort().join('\n'))
    .digest('hex')
}

function clearsGate(service: ReadinessService): boolean {
  return READINESS_DIMENSION_CODES.every(code =>
    service.scores[code] >= (service.money_path && MONEY_CRITICAL.has(code) ? 3 : 2),
  )
}

export function isReadinessSnapshot(value: unknown, nowMs = Date.now()): value is ReadinessSnapshotReport {
  if (!isRecord(value)) return false
  if (value.schema_version !== 1
    || !isDateOnly(value.generated_for)
    || !isCollectedAt(value.collected_at, nowMs)
    || typeof value.source_sha !== 'string' || !SHA1.test(value.source_sha)) return false
  if (!isRecord(value.scope)
    || value.scope.kind !== 'service-module-matrix-v1'
    || !Number.isSafeInteger(value.scope.service_count)
    || !Number.isSafeInteger(value.scope.money_path_count)
    || typeof value.scope.roster_sha256 !== 'string'
    || !SHA256.test(value.scope.roster_sha256)) return false
  if (!Array.isArray(value.dimensions) || value.dimensions.length !== READINESS_DIMENSION_CODES.length) return false
  const dimensionCodes: string[] = []
  for (const dimension of value.dimensions) {
    if (!isRecord(dimension)
      || typeof dimension.code !== 'string'
      || !READINESS_DIMENSION_CODES.includes(dimension.code as ReadinessDimensionCode)
      || typeof dimension.name !== 'string' || dimension.name.trim() === '') return false
    dimensionCodes.push(dimension.code)
  }
  if (new Set(dimensionCodes).size !== READINESS_DIMENSION_CODES.length
    || !READINESS_DIMENSION_CODES.every(code => dimensionCodes.includes(code))) return false
  if (!Array.isArray(value.services) || value.services.length === 0) return false

  const seen = new Set<string>()
  const services: ReadinessService[] = []
  for (const row of value.services) {
    if (!isRecord(row)
      || typeof row.service !== 'string' || !SERVICE_NAME.test(row.service) || seen.has(row.service)
      || typeof row.money_path !== 'boolean'
      || !isRecord(row.scores) || !isRecord(row.evidence)
      || !['GO', 'NO-GO', 'NOT-DEPLOYED'].includes(String(row.gate))) return false
    const scores = row.scores
    const evidence = row.evidence
    if (!keysMatch(scores, READINESS_DIMENSION_CODES)
      || !READINESS_DIMENSION_CODES.every(code => Number.isInteger(scores[code]) && SCORES.has(scores[code] as number))
      || !keysMatch(evidence, READINESS_DIMENSION_CODES)
      || !READINESS_DIMENSION_CODES.every(code => typeof evidence[code] === 'string')) return false
    const service = row as unknown as ReadinessService
    // A verified snapshot may report a failed gate, but it must never claim GO below the
    // collector's established threshold. This validates evidence; it does not rescore it.
    if ((service.gate === 'GO') !== clearsGate(service)) return false
    seen.add(service.service)
    services.push(service)
  }

  const moneyPathCount = services.filter(service => service.money_path).length
  return value.scope.service_count === services.length
    && value.scope.money_path_count === moneyPathCount
    && value.scope.roster_sha256 === rosterDigest(services)
}

export function readinessRosterDigest(services: readonly Pick<ReadinessService, 'service' | 'money_path'>[]): string {
  return createHash('sha256')
    .update(services.map(service => `${service.service}:${service.money_path}`).sort().join('\n'))
    .digest('hex')
}
