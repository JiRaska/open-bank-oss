// SPDX-License-Identifier: Apache-2.0

export interface AuthorityEvidence {
  delegationId: string; revision: number; eventType: string; grantorPartyId: string; granteePartyId: string
  resourceType: string | null; resourceId: string | null; capabilities: string[]; approvalPolicy: string | null
  requiredApprovals: number | null; validFrom: string | null; validTo: string | null; occurredAt: string
}
export interface RecordedAuthorityEvidence {
  evidence: AuthorityEvidence; recordedAt: string; evidenceRef: string; contentHash: string
}
export interface AuthorityHistory {
  root: string; effectiveAt: string; knownAt: string; observations: RecordedAuthorityEvidence[]
  truncated: boolean; actionAuthorization: 'UNKNOWN'
}
export const AUTHORITY_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ENUM = /^[A-Z][A-Z0-9_]{0,79}$/
const EVENTS = new Set(['DelegationOffered', 'DelegationActivated', 'DelegationReinstated', 'DelegationDeclined', 'DelegationRevoked', 'DelegationSuspended', 'DelegationRenounced', 'DelegationExpired'])
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid authority object')
  return value as Record<string, unknown>
}
function text(value: unknown, max = 200): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u001f]/.test(value)) throw new Error('Invalid authority field')
  return value
}
function uuid(value: unknown): string {
  const result = text(value, 36)
  if (!AUTHORITY_UUID.test(result)) throw new Error('Invalid authority UUID')
  return result.toLowerCase()
}
export function authorityTimestamp(value: unknown): string {
  const result = text(value, 40)
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(result) || !Number.isFinite(Date.parse(result))) throw new Error('Invalid authority timestamp')
  if (Number(result.slice(11, 13)) > 23 || Number(result.slice(14, 16)) > 59 || Number(result.slice(17, 19)) > 59) throw new Error('Invalid authority time')
  const date = result.slice(0, 10)
  if (new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date) throw new Error('Invalid authority date')
  return result
}
function enumValue(value: unknown): string {
  const result = text(value, 80)
  if (!ENUM.test(result)) throw new Error('Invalid authority enum')
  return result
}
function nullable<T>(value: unknown, parse: (v: unknown) => T): T | null { return value === null ? null : parse(value) }
export function parseAuthorityHistory(value: unknown): AuthorityHistory {
  const body = object(value)
  const root = text(body.root, 47)
  if (!root.startsWith('delegation:')) throw new Error('Invalid authority root')
  const id = uuid(root.slice(11))
  const effectiveAt = authorityTimestamp(body.effectiveAt), knownAt = authorityTimestamp(body.knownAt)
  if (body.actionAuthorization !== 'UNKNOWN' || typeof body.truncated !== 'boolean' || !Array.isArray(body.observations) || body.observations.length > 100) throw new Error('Invalid authority history')
  let previous = Infinity
  const references = new Set<string>()
  const observations = body.observations.map(raw => {
    const row = object(raw), source = object(row.evidence)
    const revision = source.revision
    if (typeof revision !== 'number' || !Number.isSafeInteger(revision) || revision < 0 || revision >= previous) throw new Error('Invalid authority revision order')
    previous = revision
    const eventType = text(source.eventType, 80)
    if (!EVENTS.has(eventType) || uuid(source.delegationId) !== id) throw new Error('Invalid authority event')
    if (!Array.isArray(source.capabilities) || source.capabilities.length > 100) throw new Error('Invalid authority capabilities')
    const capabilities = source.capabilities.map(enumValue)
    if (capabilities.some((item, index) => index > 0 && item <= capabilities[index - 1])) throw new Error('Invalid authority capability order')
    const requiredApprovals = source.requiredApprovals
    if (requiredApprovals !== null && (typeof requiredApprovals !== 'number' || !Number.isInteger(requiredApprovals) || requiredApprovals < 1 || requiredApprovals > 100)) throw new Error('Invalid authority approvals')
    const evidence: AuthorityEvidence = {
      delegationId: id, revision, eventType, grantorPartyId: uuid(source.grantorPartyId), granteePartyId: uuid(source.granteePartyId),
      resourceType: nullable(source.resourceType, enumValue), resourceId: nullable(source.resourceId, uuid), capabilities,
      approvalPolicy: nullable(source.approvalPolicy, enumValue), requiredApprovals: requiredApprovals as number | null,
      validFrom: nullable(source.validFrom, authorityTimestamp), validTo: nullable(source.validTo, authorityTimestamp), occurredAt: authorityTimestamp(source.occurredAt),
    }
    if (evidence.validFrom && evidence.validTo && Date.parse(evidence.validTo) <= Date.parse(evidence.validFrom)) throw new Error('Invalid authority validity')
    if (['DelegationActivated', 'DelegationReinstated'].includes(eventType) && (!evidence.resourceType || !evidence.resourceId || !evidence.validFrom || !capabilities.length)) throw new Error('Incomplete authority evidence')
    const recordedAt = authorityTimestamp(row.recordedAt), evidenceRef = text(row.evidenceRef), contentHash = text(row.contentHash, 64)
    if (!/^[a-f0-9]{64}$/.test(contentHash) || references.has(evidenceRef) || Date.parse(recordedAt) > Date.parse(knownAt) || Date.parse(evidence.occurredAt) > Date.parse(effectiveAt)) throw new Error('Invalid authority provenance')
    references.add(evidenceRef)
    return { evidence, recordedAt, evidenceRef, contentHash }
  })
  return { root: `delegation:${id}`, effectiveAt, knownAt, observations, truncated: body.truncated, actionAuthorization: 'UNKNOWN' }
}
