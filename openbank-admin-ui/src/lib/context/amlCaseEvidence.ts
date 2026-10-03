// SPDX-License-Identifier: Apache-2.0
import { AUTHORITY_UUID, authorityTimestamp } from './authorityHistory'

export interface AmlCaseObservation {
  eventId: string; caseId: string; partyId: string; accountId: string | null; transactionId: string | null
  eventType: 'aml.case.created.v1' | 'aml.case.status_changed.v1'; status: string; previousStatus: string | null
  riskLevel: string | null; screeningType: string | null; occurredAt: string
}
export interface RecordedAmlCaseEvidence {
  evidence: AmlCaseObservation; recordedAt: string; evidenceRef: string; contentHash: string
}
export interface AmlCaseEvidenceHistory {
  root: string; effectiveAt: string; knownAt: string; truncated: boolean; observations: RecordedAmlCaseEvidence[]
}
export interface AmlCaseNetwork { root: AmlCaseEvidenceHistory; related: AmlCaseEvidenceHistory[] }
const STATUSES = new Set(['OPEN', 'UNDER_REVIEW', 'CLEARED', 'BLOCKED', 'ESCALATED'])
const RISKS = new Set(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
const SCREENINGS = new Set(['CUSTOMER_ONBOARDING', 'TRANSACTION_MONITORING', 'PERIODIC_REVIEW', 'MANUAL_INVESTIGATION'])
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid AML object')
  return value as Record<string, unknown>
}
function text(value: unknown, max = 100): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\u0000-\u001f]/.test(value)) throw new Error('Invalid AML field')
  return value
}
function uuid(value: unknown): string {
  const result = text(value, 36)
  if (!AUTHORITY_UUID.test(result)) throw new Error('Invalid AML UUID')
  return result.toLowerCase()
}
function choice(value: unknown, allowed: Set<string>): string {
  const result = text(value, 80)
  if (!allowed.has(result)) throw new Error('Invalid AML enum')
  return result
}
function nullable<T>(value: unknown, parse: (v: unknown) => T): T | null { return value === null ? null : parse(value) }
/** Compare source instants without dropping PostgreSQL/JVM sub-millisecond precision. */
export function amlTimestampNanos(value: unknown): bigint {
  const timestamp = authorityTimestamp(value)
  const fraction = /\.(\d{1,9})/.exec(timestamp)?.[1] ?? ''
  const seconds = timestamp.replace(/\.\d{1,9}/, '')
  return BigInt(Date.parse(seconds)) * BigInt(1_000_000) + BigInt(fraction.padEnd(9, '0'))
}
export function parseAmlCaseEvidence(value: unknown): AmlCaseEvidenceHistory {
  const body = object(value), rawRoot = text(body.root, 45)
  if (!rawRoot.startsWith('aml-case:')) throw new Error('Invalid AML root')
  const id = uuid(rawRoot.slice(9)), effectiveAt = authorityTimestamp(body.effectiveAt), knownAt = authorityTimestamp(body.knownAt)
  if (typeof body.truncated !== 'boolean' || !Array.isArray(body.observations) || body.observations.length > 100) throw new Error('Invalid AML history')
  const events = new Set<string>()
  let previousTime: bigint | null = null, previousId = ''
  const observations = body.observations.map(raw => {
    const row = object(raw), source = object(row.evidence), eventId = uuid(source.eventId)
    const eventType = text(source.eventType)
    if (eventType !== 'aml.case.created.v1' && eventType !== 'aml.case.status_changed.v1') throw new Error('Invalid AML event')
    if (uuid(source.caseId) !== id || events.has(eventId)) throw new Error('Invalid AML identity')
    events.add(eventId)
    const evidence: AmlCaseObservation = {
      eventId, caseId: id, partyId: uuid(source.partyId), accountId: nullable(source.accountId, uuid), transactionId: nullable(source.transactionId, uuid),
      eventType, status: choice(source.status, STATUSES), previousStatus: nullable(source.previousStatus, value => choice(value, STATUSES)),
      riskLevel: nullable(source.riskLevel, value => choice(value, RISKS)), screeningType: nullable(source.screeningType, value => choice(value, SCREENINGS)), occurredAt: authorityTimestamp(source.occurredAt),
    }
    if (eventType === 'aml.case.created.v1') {
      if (evidence.previousStatus !== null || !evidence.riskLevel || !evidence.screeningType || evidence.status !== (['HIGH', 'CRITICAL'].includes(evidence.riskLevel) ? 'UNDER_REVIEW' : 'OPEN')) throw new Error('Invalid AML creation evidence')
    } else if (!evidence.previousStatus || evidence.riskLevel !== null || evidence.screeningType !== null || evidence.accountId !== null || evidence.transactionId !== null) throw new Error('Invalid AML status evidence')
    const recordedAt = authorityTimestamp(row.recordedAt), evidenceRef = text(row.evidenceRef), contentHash = text(row.contentHash, 64)
    const time = amlTimestampNanos(evidence.occurredAt)
    if (evidenceRef !== `aml-case:${id}:${eventId}` || !/^[a-f0-9]{64}$/.test(contentHash) || time > amlTimestampNanos(effectiveAt) || amlTimestampNanos(recordedAt) > amlTimestampNanos(knownAt)) throw new Error('Invalid AML provenance')
    if (previousTime !== null && (time > previousTime || (time === previousTime && eventId <= previousId))) throw new Error('Invalid AML observation order')
    previousTime = time; previousId = eventId
    return { evidence, recordedAt, evidenceRef, contentHash }
  })
  return { root: `aml-case:${id}`, effectiveAt, knownAt, truncated: body.truncated, observations }
}

/** A relationship is shown only when both approved histories name the same source identifier. */
export function amlReferences(history: AmlCaseEvidenceHistory): string[] {
  return [...new Set(history.observations.flatMap(({ evidence }) => [
    `party:${evidence.partyId}`,
    ...(evidence.accountId ? [`account:${evidence.accountId}`] : []),
    ...(evidence.transactionId ? [`transaction:${evidence.transactionId}`] : []),
  ]))]
}
export function sharedAmlReferences(a: AmlCaseEvidenceHistory, b: AmlCaseEvidenceHistory): string[] {
  const left = new Set(amlReferences(a))
  return amlReferences(b).filter(id => left.has(id))
}
export function parseAmlCaseNetwork(value: unknown): AmlCaseNetwork {
  const body = object(value)
  const root = parseAmlCaseEvidence(body.root)
  if (!Array.isArray(body.related) || body.related.length > 4) throw new Error('Invalid AML network')
  const seen = new Set([root.root])
  const related = body.related.map(raw => {
    const history = parseAmlCaseEvidence(raw)
    if (seen.has(history.root) || history.effectiveAt !== root.effectiveAt || history.knownAt !== root.knownAt || !sharedAmlReferences(root, history).length) throw new Error('Invalid AML relationship')
    seen.add(history.root)
    return history
  })
  return { root, related }
}
