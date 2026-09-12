// SPDX-License-Identifier: Apache-2.0

export const FLEET_GROUPS = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform'] as const
export type FleetGroup = typeof FLEET_GROUPS[number]

export type GovernanceFleetMember = { name: string; group: FleetGroup }
export type DashboardHealthEntry = {
  name: string
  label: string
  group: FleetGroup
  status: 'UP' | 'DOWN' | 'UNKNOWN'
  latencyMs: number | null
}

const IDENTIFIER = /^[a-z][a-z0-9-]{0,127}$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Expected object')
  return value as Record<string, unknown>
}

function text(value: unknown, max = 200): string {
  if (typeof value !== 'string' || value.trim() === '' || value.length > max) throw new Error('Invalid text')
  return value
}

function identifier(value: unknown): string {
  const result = text(value, 128)
  if (!IDENTIFIER.test(result)) throw new Error('Invalid service identifier')
  return result
}

function group(value: unknown): FleetGroup {
  if (typeof value !== 'string' || !FLEET_GROUPS.includes(value as FleetGroup)) throw new Error('Invalid fleet group')
  return value as FleetGroup
}

function unique<T>(items: T[], key: (item: T) => string): T[] {
  if (new Set(items.map(key)).size !== items.length) throw new Error('Duplicate service')
  return items
}

export function parseGovernanceFleet(value: unknown): GovernanceFleetMember[] {
  const body = record(value)
  if (body.available !== true || !Array.isArray(body.items) || body.items.length === 0 || body.items.length > 100) {
    throw new Error('Governance evidence unavailable')
  }
  if (typeof body.timestamp !== 'string' || !RFC3339.test(body.timestamp) || !Number.isFinite(Date.parse(body.timestamp))) {
    throw new Error('Invalid governance timestamp')
  }
  return unique(body.items.map(value => {
    const item = record(value)
    return { name: identifier(item.serviceName), group: group(item.dataDomain) }
  }), item => item.name)
}

export function parseDashboardHealth(value: unknown): DashboardHealthEntry[] {
  const body = record(value)
  if (!Array.isArray(body.services) || body.services.length > 100) throw new Error('Health evidence unavailable')
  return unique(body.services.map(value => {
    const item = record(value)
    const status = item.status
    if (status !== 'UP' && status !== 'DOWN' && status !== 'UNKNOWN') throw new Error('Invalid health status')
    const latencyMs = item.latencyMs
    if (latencyMs !== null && (typeof latencyMs !== 'number' || !Number.isFinite(latencyMs) || latencyMs < 0)) {
      throw new Error('Invalid health latency')
    }
    return {
      name: identifier(item.name),
      label: text(item.label),
      group: group(item.group),
      status,
      latencyMs,
    }
  }), item => item.name)
}
