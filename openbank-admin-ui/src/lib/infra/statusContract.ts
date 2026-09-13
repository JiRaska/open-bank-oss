// SPDX-License-Identifier: Apache-2.0

export type InfrastructureStatus = 'UP' | 'DOWN' | 'UNKNOWN'

export interface InfrastructureStatusResult {
  id: string
  status: InfrastructureStatus
  latencyMs: number | null
  checkedAt: string | null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function parseInfrastructureStatuses(raw: unknown): Record<string, InfrastructureStatusResult> {
  if (!isRecord(raw) || Object.keys(raw).length === 0) throw new Error('Invalid infrastructure status map')
  const result: Record<string, InfrastructureStatusResult> = {}
  for (const [key, value] of Object.entries(raw)) {
    if (!isRecord(value) || value.id !== key || !['UP', 'DOWN', 'UNKNOWN'].includes(String(value.status))) {
      throw new Error('Invalid infrastructure status entry')
    }
    const latencyMs = value.latencyMs
    if (latencyMs !== null && (typeof latencyMs !== 'number' || !Number.isFinite(latencyMs) || latencyMs < 0)) {
      throw new Error('Invalid infrastructure latency')
    }
    const checkedAt = value.checkedAt
    if (checkedAt !== null && (typeof checkedAt !== 'string' || Number.isNaN(Date.parse(checkedAt)))) {
      throw new Error('Invalid infrastructure checkedAt')
    }
    result[key] = {
      id: key,
      status: value.status as InfrastructureStatus,
      latencyMs,
      checkedAt,
    }
  }
  return result
}
