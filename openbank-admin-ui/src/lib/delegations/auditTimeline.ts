// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Narrow admin projection of audit-service's append-only evidence for one delegation grant.
 *
 * Raw payloads deliberately stop at the BFF. The detail page needs the lifecycle action, actor,
 * time, reason and evidence references; it does not need every field a producer may have put in
 * the regulated audit envelope.
 */
export type DelegationAuditActorProvenance = 'declared' | 'classified' | 'absent'
export type DelegationAuditReasonState = 'recorded' | 'not_recorded' | 'unreadable'
export type DelegationAuditTimeSource = 'event' | 'ingest' | 'unknown'
export type DelegationAuditSourceAttribution = 'event' | 'topic' | 'unknown'

export interface DelegationAuditTimelineEntry {
  evidenceId: string
  eventType: string
  occurredAt: string
  recordedAt: string | null
  timeSource: DelegationAuditTimeSource
  actorId: string | null
  actorType: string | null
  actorProvenance: DelegationAuditActorProvenance
  reason: string | null
  reasonState: DelegationAuditReasonState
  reasonTruncated: boolean
  statusAfter: string | null
  sourceService: string | null
  sourceAttribution: DelegationAuditSourceAttribution
  correlationId: string | null
}

export interface DelegationAuditTimelineResponse {
  grantId: string
  entries: DelegationAuditTimelineEntry[]
  latestStatusAfter: string | null
  mayBeTruncated: boolean
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const MAX_REASON_LENGTH = 1_000

const STATUS_AFTER_EVENT: Readonly<Record<string, string>> = {
  DelegationOffered: 'OFFERED',
  DelegationActivated: 'ACTIVE',
  DelegationDeclined: 'DECLINED',
  DelegationRevoked: 'REVOKED',
  DelegationSuspended: 'SUSPENDED',
  DelegationReinstated: 'ACTIVE',
  DelegationRenounced: 'RENOUNCED',
  DelegationExpired: 'EXPIRED',
}

function isDelegationGrantAggregate(value: string): boolean {
  // audit-service uppercases newly ingested producer declarations, while the append-only trail
  // deliberately retains the pre-normalization `DelegationGrant` spelling. Punctuation is not
  // semantically significant here, but no other aggregate type is accepted.
  return value.replace(/[^a-z0-9]/gi, '').toUpperCase() === 'DELEGATIONGRANT'
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function boundedString(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed && trimmed.length <= max ? trimmed : null
}

function canonicalTime(value: unknown): string | null {
  if (typeof value !== 'string' || !value.trim()) return null
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

function payloadRecord(value: unknown): { value: Record<string, unknown> | null; unreadable: boolean } {
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value) as unknown
      const object = record(parsed)
      return { value: object, unreadable: object === null }
    } catch {
      return { value: null, unreadable: true }
    }
  }
  const object = record(value)
  return { value: object, unreadable: object === null }
}

function reasonFromPayload(payload: unknown): Pick<DelegationAuditTimelineEntry, 'reason' | 'reasonState' | 'reasonTruncated'> {
  const parsed = payloadRecord(payload)
  if (parsed.unreadable) return { reason: null, reasonState: 'unreadable', reasonTruncated: false }

  const rawReason = parsed.value?.reason
  if (rawReason === undefined || rawReason === null || rawReason === '') {
    return { reason: null, reasonState: 'not_recorded', reasonTruncated: false }
  }
  if (typeof rawReason !== 'string') {
    return { reason: null, reasonState: 'unreadable', reasonTruncated: false }
  }
  const reason = rawReason.trim()
  if (!reason) return { reason: null, reasonState: 'not_recorded', reasonTruncated: false }
  if (reason.length <= MAX_REASON_LENGTH) {
    return { reason, reasonState: 'recorded', reasonTruncated: false }
  }
  return {
    reason: `${reason.slice(0, MAX_REASON_LENGTH)}…`,
    reasonState: 'recorded',
    reasonTruncated: true,
  }
}

function projectEntry(raw: unknown, grantId: string): DelegationAuditTimelineEntry | null {
  const item = record(raw)
  if (!item) return null

  const aggregateId = boundedString(item.aggregateId, 36)
  const aggregateType = boundedString(item.aggregateType, 80)
  const evidenceId = boundedString(item.id, 36)
  const eventType = boundedString(item.eventType, 128)
  const occurredAt = canonicalTime(item.occurredAt)

  // A response to /entries/{grantId} that points at another aggregate is not partial success.
  // Refuse the whole projection instead of presenting evidence under the wrong grant.
  if (
    !aggregateId || aggregateId.toLowerCase() !== grantId.toLowerCase() ||
    !aggregateType || !isDelegationGrantAggregate(aggregateType) ||
    !evidenceId || !UUID_RE.test(evidenceId) ||
    !eventType || !occurredAt
  ) return null

  const actorId = boundedString(item.actorId, 256)
  const actorType = boundedString(item.actorType, 80)
  const actorProvenance: DelegationAuditActorProvenance = actorId
    ? 'declared'
    : actorType ? 'classified' : 'absent'

  const source = boundedString(item.sourceService, 120)
  const sourceService = source?.toLowerCase() === 'unknown' ? null : source
  const sourceAttribution: DelegationAuditSourceAttribution = sourceService && item.sourceServiceSource === 'EVENT'
    ? 'event'
    : sourceService && item.sourceServiceSource === 'TOPIC' ? 'topic' : 'unknown'
  const timeSource: DelegationAuditTimeSource = item.occurredAtSource === 'EVENT'
    ? 'event'
    : item.occurredAtSource === 'INGEST' ? 'ingest' : 'unknown'

  return {
    evidenceId,
    eventType,
    occurredAt,
    recordedAt: canonicalTime(item.recordedAt),
    timeSource,
    actorId,
    actorType,
    actorProvenance,
    ...reasonFromPayload(item.payload),
    statusAfter: STATUS_AFTER_EVENT[eventType] ?? null,
    sourceService,
    sourceAttribution,
    correlationId: boundedString(item.correlationId, 256),
  }
}

/**
 * Fail-closed projection: malformed or cross-aggregate rows reject the response as a whole.
 * Unknown future DelegationGrant event types remain visible with no invented resulting status.
 */
export function projectDelegationAuditTimeline(
  raw: unknown,
  grantId: string,
  requestedLimit = 100,
): DelegationAuditTimelineResponse | null {
  if (!UUID_RE.test(grantId) || !Array.isArray(raw)) return null

  const projected = raw.map(item => projectEntry(item, grantId))
  if (projected.some(item => item === null)) return null

  const entries = (projected as DelegationAuditTimelineEntry[])
    .map((entry, index) => ({ entry, index }))
    .sort((left, right) => {
      const byTime = Date.parse(right.entry.occurredAt) - Date.parse(left.entry.occurredAt)
      return byTime || left.index - right.index
    })
    .map(({ entry }) => entry)

  return {
    grantId,
    entries,
    latestStatusAfter: entries.find(entry => entry.statusAfter !== null)?.statusAfter ?? null,
    mayBeTruncated: raw.length >= requestedLimit,
  }
}
