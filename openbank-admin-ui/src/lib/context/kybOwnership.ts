// SPDX-License-Identifier: Apache-2.0

export const KYB_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const SHA256 = /^[0-9a-f]{64}$/

export interface OwnershipReference {
  observationId: string
  revision: number
  sourceSha256: string
  recordedAt: string
}

export interface OwnershipHistory {
  root: string
  knownAt: string
  observations: OwnershipReference[]
  truncated: boolean
}

export interface OwnershipDetail {
  caseId: string
  observationId: string
  revision: number
  sourceSha256: string
  source: string
  fetchedAt: string
  owners: Array<{ fullName: string; band: string; corporate: boolean; natureOfControl: string[] }>
}

const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid evidence object')
  return value as Record<string, unknown>
}

const date = (value: unknown): string => {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) throw new Error('Invalid evidence time')
  return value
}

export function parseOwnershipHistory(value: unknown): OwnershipHistory {
  const row = object(value)
  if (typeof row.root !== 'string' || !/^kyb-case:[0-9a-f-]{36}$/i.test(row.root)) throw new Error('Invalid root')
  if (!Array.isArray(row.observations) || row.observations.length > 50 || typeof row.truncated !== 'boolean') throw new Error('Invalid history')
  return {
    root: row.root, knownAt: date(row.knownAt), truncated: row.truncated,
    observations: row.observations.map(value => {
      const item = object(value)
      if (typeof item.observationId !== 'string' || !KYB_UUID.test(item.observationId) ||
          !Number.isSafeInteger(item.revision) || (item.revision as number) < 1 ||
          typeof item.sourceSha256 !== 'string' || !SHA256.test(item.sourceSha256)) throw new Error('Invalid reference')
      return {
        observationId: item.observationId, revision: item.revision as number,
        sourceSha256: item.sourceSha256, recordedAt: date(item.recordedAt),
      }
    }),
  }
}

export function parseOwnershipDetail(value: unknown, reference: OwnershipReference, caseId: string): OwnershipDetail {
  const row = object(value), finding = object(row.finding)
  if (row.caseId !== caseId || row.id !== reference.observationId || row.revision !== reference.revision ||
      row.sourceSha256 !== reference.sourceSha256 || typeof finding.source !== 'string' ||
      !['REGISTER', 'SELF_DECLARATION', 'UNAVAILABLE'].includes(finding.source) ||
      !Array.isArray(finding.owners) || finding.owners.length > 100) throw new Error('Source mismatch')
  return {
    caseId, observationId: reference.observationId, revision: reference.revision,
    sourceSha256: reference.sourceSha256, source: finding.source, fetchedAt: date(finding.fetchedAt),
    owners: finding.owners.map(value => {
      const owner = object(value)
      if (typeof owner.fullName !== 'string' || owner.fullName.length > 200 ||
          typeof owner.band !== 'string' || owner.band.length > 40 ||
          typeof owner.corporate !== 'boolean' || !Array.isArray(owner.natureOfControl) ||
          owner.natureOfControl.length > 20 || owner.natureOfControl.some(v => typeof v !== 'string' || v.length > 100)) {
        throw new Error('Invalid owner evidence')
      }
      return {
        fullName: owner.fullName, band: owner.band,
        corporate: owner.corporate, natureOfControl: owner.natureOfControl as string[],
      }
    }),
  }
}
