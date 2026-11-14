export const AML_STATUSES = ['OPEN', 'UNDER_REVIEW', 'CLEARED', 'BLOCKED', 'ESCALATED'] as const
export const AML_RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
export const SCREENING_TYPES = ['CUSTOMER_ONBOARDING', 'TRANSACTION_MONITORING', 'PERIODIC_REVIEW', 'MANUAL_INVESTIGATION'] as const

export interface AmlCase {
  id: string; partyId: string; accountId: string | null; transactionId: string | null
  customerReference: string; screeningType: (typeof SCREENING_TYPES)[number]
  riskLevel: (typeof AML_RISK_LEVELS)[number]; status: (typeof AML_STATUSES)[number]
  alertCode: string; alertDetail: string | null; matchedEntity: string | null
  decisionReason: string | null; assignedAnalyst: string | null; decidedBy: string | null
  screenedAt: string; decidedAt: string | null; createdAt: string; updatedAt: string
  /**
   * The case closes only through a four-eyes disposition (aml-service ADR-0371 §5). Absent on an
   * aml-service older than openapi 1.5.0, which reads as false: the direct decision is then offered
   * and the service still refuses it for a pack-monitoring case, so the fallback is safe.
   */
  requiresFourEyesDisposition: boolean
}

const record = (value: unknown): Record<string, unknown> => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid AML case')
  return value as Record<string, unknown>
}
const string = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid AML case ${field}`)
  return value
}
const optionalString = (value: unknown, field: string): string | null =>
  value === null || value === undefined ? null : string(value, field)
const enumValue = <T extends readonly string[]>(value: unknown, allowed: T, field: string): T[number] => {
  if (typeof value !== 'string' || !allowed.includes(value)) throw new Error(`Invalid AML case ${field}`)
  return value as T[number]
}
const instant = (value: unknown, field: string): string => {
  const parsed = string(value, field)
  if (!Number.isFinite(Date.parse(parsed))) throw new Error(`Invalid AML case ${field}`)
  return parsed
}
const optionalInstant = (value: unknown, field: string): string | null =>
  value === null || value === undefined ? null : instant(value, field)

export function parseAmlCases(raw: unknown): AmlCase[] {
  if (!Array.isArray(raw)) throw new Error('Invalid AML cases response')
  return raw.map(parseAmlCase)
}

/** One case, as `GET /api/v1/aml/cases/{id}` answers it. Same fail-fast contract as the list. */
export function parseAmlCase(value: unknown): AmlCase {
  const item = record(value)
  return {
    id: string(item.id, 'id'), partyId: string(item.partyId, 'partyId'),
    accountId: optionalString(item.accountId, 'accountId'), transactionId: optionalString(item.transactionId, 'transactionId'),
    customerReference: string(item.customerReference, 'customerReference'),
    screeningType: enumValue(item.screeningType, SCREENING_TYPES, 'screeningType'),
    riskLevel: enumValue(item.riskLevel, AML_RISK_LEVELS, 'riskLevel'),
    status: enumValue(item.status, AML_STATUSES, 'status'), alertCode: string(item.alertCode, 'alertCode'),
    alertDetail: optionalString(item.alertDetail, 'alertDetail'), matchedEntity: optionalString(item.matchedEntity, 'matchedEntity'),
    decisionReason: optionalString(item.decisionReason, 'decisionReason'),
    assignedAnalyst: optionalString(item.assignedAnalyst, 'assignedAnalyst'), decidedBy: optionalString(item.decidedBy, 'decidedBy'),
    screenedAt: instant(item.screenedAt, 'screenedAt'), decidedAt: optionalInstant(item.decidedAt, 'decidedAt'),
    createdAt: instant(item.createdAt, 'createdAt'), updatedAt: instant(item.updatedAt, 'updatedAt'),
    requiresFourEyesDisposition: item.requiresFourEyesDisposition === true,
  }
}

export type AmlStatus = AmlCase['status']

/**
 * Mirror of `AmlCase.canTransitionTo` in aml-service. The service is the authority and refuses an
 * illegal move with 400; the UI only uses this to avoid OFFERING a button the service would refuse,
 * so a mismatch degrades to an error message, never to a wrong decision.
 */
const TRANSITIONS: Record<AmlStatus, readonly AmlStatus[]> = {
  OPEN: ['UNDER_REVIEW', 'CLEARED', 'BLOCKED', 'ESCALATED'],
  UNDER_REVIEW: ['CLEARED', 'BLOCKED', 'ESCALATED'],
  ESCALATED: ['UNDER_REVIEW', 'CLEARED', 'BLOCKED'],
  CLEARED: [],
  BLOCKED: [],
}

export function allowedTransitions(status: AmlStatus): readonly AmlStatus[] {
  return TRANSITIONS[status]
}

export const isOpenAmlStatus = (status: AmlStatus): boolean => TRANSITIONS[status].length > 0

/**
 * Reason is mandatory for every DECISION in the UI, not only for BLOCKED (the one the service
 * enforces): a cleared or escalated AML case without a written rationale is not defensible to a
 * supervisor or the FAU. Taking a case into review is a workflow step, not a decision.
 */
export const requiresReason = (target: AmlStatus): boolean => target !== 'UNDER_REVIEW'

/** Display name for a party as party-service projects it to staff; null when it carries none. */
export function partyDisplayName(party: { legalName?: string | null; tradingName?: string | null } | null | undefined): string | null {
  const name = party?.legalName?.trim() || party?.tradingName?.trim()
  return name ? name : null
}

/** Whole hours a case has been open; the SLA signal an officer triages by. */
export function caseAgeHours(createdAt: string, now: Date = new Date()): number {
  return Math.max(0, Math.floor((now.getTime() - Date.parse(createdAt)) / 3_600_000))
}
