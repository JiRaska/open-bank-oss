// SPDX-License-Identifier: Apache-2.0

export const DISPUTE_STATUSES = [
  'OPEN',
  'UNDER_REVIEW',
  'PENDING_CUSTOMER',
  'PENDING_MERCHANT',
  'RESOLVED_CUSTOMER',
  'RESOLVED_MERCHANT',
  'WITHDRAWN',
  'ESCALATED',
] as const

export type DisputeStatus = typeof DISPUTE_STATUSES[number]

export interface DisputeRecord {
  id: string
  reference: string
  disputeType: string
  status: DisputeStatus
  accountId: string
  transactionId: string
  amount: number
  currency: string
  resolutionDeadline: string | null
  createdAt: string
}

const STATUS_SET: ReadonlySet<string> = new Set(DISPUTE_STATUSES)
const TERMINAL_STATUSES: ReadonlySet<DisputeStatus> = new Set([
  'RESOLVED_CUSTOMER',
  'RESOLVED_MERCHANT',
  'WITHDRAWN',
])
const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isLocalDate(value: unknown): value is string {
  if (typeof value !== 'string' || !LOCAL_DATE.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const parsed = new Date(Date.UTC(year, month - 1, day))
  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day
}

function isDispute(value: unknown): value is DisputeRecord {
  if (!value || typeof value !== 'object') return false
  const dispute = value as Partial<DisputeRecord>
  return isNonEmptyString(dispute.id)
    && isNonEmptyString(dispute.reference)
    && isNonEmptyString(dispute.disputeType)
    && typeof dispute.status === 'string'
    && STATUS_SET.has(dispute.status)
    && isNonEmptyString(dispute.accountId)
    && isNonEmptyString(dispute.transactionId)
    && typeof dispute.amount === 'number'
    && Number.isFinite(dispute.amount)
    && dispute.amount >= 0
    && typeof dispute.currency === 'string'
    && /^[A-Z]{3}$/.test(dispute.currency)
    && (dispute.resolutionDeadline === null || isLocalDate(dispute.resolutionDeadline))
    && typeof dispute.createdAt === 'string'
    && Number.isFinite(Date.parse(dispute.createdAt))
}

export function parseDisputeList(value: unknown): DisputeRecord[] {
  if (!Array.isArray(value) || !value.every(isDispute)) {
    throw new Error('invalid dispute portfolio payload')
  }
  return value
}

export function isTerminalDispute(status: DisputeStatus): boolean {
  return TERMINAL_STATUSES.has(status)
}

/** Whole calendar days until an inclusive service-owned LocalDate deadline. */
export function disputeDaysRemaining(deadline: string, today: Date): number {
  if (!isLocalDate(deadline)) throw new Error('invalid dispute deadline')
  const [year, month, day] = deadline.split('-').map(Number)
  const deadlineDay = Date.UTC(year, month - 1, day)
  const todayDay = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((deadlineDay - todayDay) / 86_400_000)
}

export function isDisputeSlaBreached(dispute: DisputeRecord, today: Date): boolean {
  return !isTerminalDispute(dispute.status)
    && dispute.resolutionDeadline !== null
    && disputeDaysRemaining(dispute.resolutionDeadline, today) < 0
}
