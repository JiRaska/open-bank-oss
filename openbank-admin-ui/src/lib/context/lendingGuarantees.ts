// SPDX-License-Identifier: Apache-2.0
import { AUTHORITY_UUID, authorityTimestamp } from './authorityHistory'

export type ApprovedGuarantee = {
  guaranteeId: string; contractId: string; revision: number; supersedesGuaranteeId: string | null
  guarantorPartyId: string; capAmount: number; currency: string; coverageFraction: number
  seniority: number; validFrom: string; validTo: string | null; sourceDocumentId: string
  sourceSha256: string; decidedAt: string
}
export type ApprovedGuaranteeHistory = {
  loanId: string; effectiveAt: string; knownAt: string; guarantees: ApprovedGuarantee[]; truncated: boolean
}
const record = (v: unknown): Record<string, unknown> => {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('Invalid evidence')
  return v as Record<string, unknown>
}
const uuid = (v: unknown): string => {
  if (typeof v !== 'string' || !AUTHORITY_UUID.test(v)) throw new Error('Invalid UUID')
  return v.toLowerCase()
}
const optionalUuid = (v: unknown): string | null => v == null ? null : uuid(v)
const instant = (v: unknown): string => authorityTimestamp(v)
const optionalInstant = (v: unknown): string | null => v == null ? null : instant(v)
const number = (v: unknown, min: number, max: number): number => {
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max) throw new Error('Invalid number')
  return v
}
const integer = (v: unknown, min: number): number => {
  const n = number(v, min, Number.MAX_SAFE_INTEGER)
  if (!Number.isSafeInteger(n)) throw new Error('Invalid integer')
  return n
}

export function parseApprovedGuaranteeHistory(value: unknown): ApprovedGuaranteeHistory {
  const body = record(value)
  const loanId = uuid(body.loanId)
  const effectiveAt = instant(body.effectiveAt), knownAt = instant(body.knownAt)
  if (!Array.isArray(body.guarantees) || body.guarantees.length > 100 || typeof body.truncated !== 'boolean') throw new Error('Invalid history')
  const ids = new Set<string>()
  const guarantees = body.guarantees.map(raw => {
    const row = record(raw)
    const guaranteeId = uuid(row.guaranteeId)
    if (ids.has(guaranteeId)) throw new Error('Duplicate guarantee')
    ids.add(guaranteeId)
    if (typeof row.currency !== 'string' || !/^[A-Z]{3}$/.test(row.currency)) throw new Error('Invalid currency')
    if (typeof row.sourceSha256 !== 'string' || !/^[0-9a-fA-F]{64}$/.test(row.sourceSha256)) throw new Error('Invalid source hash')
    const fact: ApprovedGuarantee = {
      guaranteeId, contractId: uuid(row.contractId), revision: integer(row.revision, 1),
      supersedesGuaranteeId: optionalUuid(row.supersedesGuaranteeId), guarantorPartyId: uuid(row.guarantorPartyId),
      capAmount: number(row.capAmount, 0, Number.MAX_VALUE), currency: row.currency,
      coverageFraction: number(row.coverageFraction, 0, 1), seniority: integer(row.seniority, 0),
      validFrom: instant(row.validFrom), validTo: optionalInstant(row.validTo), sourceDocumentId: uuid(row.sourceDocumentId),
      sourceSha256: row.sourceSha256.toLowerCase(), decidedAt: instant(row.decidedAt),
    }
    if (fact.validTo && Date.parse(fact.validTo) <= Date.parse(fact.validFrom)) throw new Error('Invalid validity')
    return fact
  })
  return { loanId, effectiveAt, knownAt, guarantees, truncated: body.truncated }
}
