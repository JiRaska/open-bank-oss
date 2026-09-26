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
  return raw.map((value) => {
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
    }
  })
}
