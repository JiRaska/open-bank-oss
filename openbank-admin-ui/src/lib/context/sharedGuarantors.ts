// SPDX-License-Identifier: Apache-2.0
import { AUTHORITY_UUID, authorityTimestamp } from './authorityHistory'
import { parseApprovedGuaranteeHistory, type ApprovedGuarantee } from './lendingGuarantees'

export type SharedGuarantorLoan = { loanId: string; guarantees: ApprovedGuarantee[]; truncated: boolean }
export type SharedGuarantorRelationships = {
  rootLoanId: string; effectiveAt: string; knownAt: string
  candidateTruncated: boolean; relatedLoansTruncated: boolean; relatedLoans: SharedGuarantorLoan[]
}

export function parseSharedGuarantorRelationships(value: unknown): SharedGuarantorRelationships {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid relationships')
  const body = value as Record<string, unknown>
  if (typeof body.rootLoanId !== 'string' || !AUTHORITY_UUID.test(body.rootLoanId)) throw new Error('Invalid root loan')
  const rootLoanId = body.rootLoanId.toLowerCase()
  const effectiveAt = authorityTimestamp(body.effectiveAt), knownAt = authorityTimestamp(body.knownAt)
  if (typeof body.candidateTruncated !== 'boolean' || typeof body.relatedLoansTruncated !== 'boolean' ||
      !Array.isArray(body.relatedLoans) || body.relatedLoans.length > 4) throw new Error('Invalid relationships')
  const seen = new Set<string>([rootLoanId])
  const relatedLoans = body.relatedLoans.map(raw => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Invalid related loan')
    const row = raw as Record<string, unknown>
    const parsed = parseApprovedGuaranteeHistory({
      loanId: row.loanId, effectiveAt, knownAt, guarantees: row.guarantees, truncated: row.truncated,
    })
    if (seen.has(parsed.loanId) || parsed.guarantees.length === 0 || parsed.guarantees.length > 20) throw new Error('Invalid related loan evidence')
    seen.add(parsed.loanId)
    return { loanId: parsed.loanId, guarantees: parsed.guarantees, truncated: parsed.truncated }
  })
  return { rootLoanId, effectiveAt, knownAt, candidateTruncated: body.candidateTruncated,
    relatedLoansTruncated: body.relatedLoansTruncated, relatedLoans }
}
