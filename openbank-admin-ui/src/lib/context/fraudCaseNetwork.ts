// SPDX-License-Identifier: Apache-2.0
import { AUTHORITY_UUID, authorityTimestamp } from '@/lib/context/authorityHistory'

export type FraudCaseEvidence = {
  caseId: string
  scoreId: string
  accountId: string
  counterpartyId: string | null
  status: 'OPEN'
  revision: number
  openedAt: string
  closedAt: null
}
export type FraudSharedReference = { type: 'ACCOUNT' | 'COUNTERPARTY'; sourceId: string }
export type FraudRelatedCase = { evidence: FraudCaseEvidence; shared: FraudSharedReference[] }
export type FraudCaseNetwork = {
  root: FraudCaseEvidence
  related: FraudRelatedCase[]
  inspectedCandidates: number
  comparedCandidates?: number
  candidateTruncated: boolean
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid Fraud evidence')
  return value as Record<string, unknown>
}
function uuid(value: unknown): string {
  if (typeof value !== 'string' || !AUTHORITY_UUID.test(value)) throw new Error('Invalid Fraud UUID')
  return value.toLowerCase()
}
function evidence(value: unknown): FraudCaseEvidence {
  const row = object(value)
  if (row.status !== 'OPEN' || !Number.isSafeInteger(row.revision) || Number(row.revision) < 1 || row.closedAt !== null) throw new Error('Inactive Fraud case')
  return {
    caseId: uuid(row.caseId), scoreId: uuid(row.scoreId), accountId: uuid(row.accountId),
    counterpartyId: row.counterpartyId === null ? null : uuid(row.counterpartyId),
    status: 'OPEN', revision: Number(row.revision), openedAt: authorityTimestamp(row.openedAt), closedAt: null,
  }
}
function explicitShared(root: FraudCaseEvidence, related: FraudCaseEvidence): FraudSharedReference[] {
  return [
    ...(root.accountId === related.accountId ? [{ type: 'ACCOUNT' as const, sourceId: root.accountId }] : []),
    ...(root.counterpartyId && root.counterpartyId === related.counterpartyId ? [{ type: 'COUNTERPARTY' as const, sourceId: root.counterpartyId }] : []),
  ]
}
export function parseFraudCaseNetwork(value: unknown): FraudCaseNetwork {
  const body = object(value), root = evidence(body.root)
  if (!Array.isArray(body.related) || body.related.length > 4 || !Number.isInteger(body.inspectedCandidates) ||
      Number(body.inspectedCandidates) < body.related.length || Number(body.inspectedCandidates) > 4 ||
      typeof body.candidateTruncated !== 'boolean') throw new Error('Invalid bounded Fraud network')
  if (body.comparedCandidates !== undefined &&
      (!Number.isInteger(body.comparedCandidates) || Number(body.comparedCandidates) < Number(body.inspectedCandidates) ||
       Number(body.comparedCandidates) > 256)) throw new Error('Invalid Fraud candidate coverage')
  const seen = new Set([root.caseId])
  const related = body.related.map(raw => {
    const row = object(raw), candidate = evidence(row.evidence)
    if (seen.has(candidate.caseId) || !Array.isArray(row.shared) || row.shared.length < 1 || row.shared.length > 2) throw new Error('Invalid Fraud relation')
    seen.add(candidate.caseId)
    const expected = explicitShared(root, candidate)
    const supplied = row.shared.map(item => {
      const ref = object(item)
      if (ref.type !== 'ACCOUNT' && ref.type !== 'COUNTERPARTY') throw new Error('Invalid Fraud reference type')
      return { type: ref.type, sourceId: uuid(ref.sourceId) }
    })
    if (JSON.stringify(supplied) !== JSON.stringify(expected)) throw new Error('Unsupported Fraud relation')
    return { evidence: candidate, shared: expected }
  })
  return {
    root, related, inspectedCandidates: Number(body.inspectedCandidates),
    comparedCandidates: body.comparedCandidates === undefined ? undefined : Number(body.comparedCandidates),
    candidateTruncated: body.candidateTruncated,
  }
}
