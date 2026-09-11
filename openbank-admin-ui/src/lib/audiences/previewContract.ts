// SPDX-License-Identifier: Apache-2.0

export type AudiencePreview =
  | { state: 'ok'; size: number; asOf: string }
  | { state: 'unauthorized' | 'unknown_segment' | 'unreachable' }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function parseAudiencePreview(raw: unknown, expectedName: string, expectedVersion: number): AudiencePreview {
  if (!isRecord(raw) || typeof raw.state !== 'string') throw new Error('Invalid audience preview')
  if (raw.state !== 'ok') {
    if (raw.state === 'unauthorized' || raw.state === 'unknown_segment' || raw.state === 'unreachable') {
      return { state: raw.state }
    }
    throw new Error('Invalid audience preview state')
  }
  if (raw.name !== expectedName || raw.version !== expectedVersion) throw new Error('Mismatched audience preview identity')
  if (typeof raw.size !== 'number' || !Number.isInteger(raw.size) || raw.size < 0) throw new Error('Invalid audience preview size')
  if (typeof raw.asOf !== 'string' || Number.isNaN(Date.parse(raw.asOf))) throw new Error('Invalid audience preview asOf')
  return { state: 'ok', size: raw.size, asOf: raw.asOf }
}
