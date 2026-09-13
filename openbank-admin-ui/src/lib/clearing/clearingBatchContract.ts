export const CLEARING_STATUSES = ['PENDING', 'IN_CLEARING', 'SETTLED', 'FAILED', 'REVERSED'] as const
export const PAYMENT_RAILS = ['SEPA_SCT', 'SEPA_SCT_INST', 'SWIFT', 'DOMESTIC', 'INTERNAL'] as const
export const SETTLEMENT_TYPES = ['GROSS', 'NET', 'DEFERRED_NET'] as const

export type ClearingStatus = (typeof CLEARING_STATUSES)[number]
export type PaymentRail = (typeof PAYMENT_RAILS)[number]

export interface ClearingBatch {
  id: string; batchReference: string; rail: PaymentRail
  settlementType: (typeof SETTLEMENT_TYPES)[number]; status: ClearingStatus
  totalDebit: number; totalCredit: number; netPosition: number; currency: string; itemCount: number
  cycleId: string | null; settlementDate: string | null; settledAt: string | null
  createdAt: string; updatedAt: string
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const requiredString = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid clearing batch ${field}`)
  return value
}

const optionalString = (value: unknown, field: string): string | null => {
  if (value === null || value === undefined) return null
  return requiredString(value, field)
}

const finiteNumber = (value: unknown, field: string): number => {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' && value.trim() !== '' ? Number(value) : NaN
  if (!Number.isFinite(parsed)) throw new Error(`Invalid clearing batch ${field}`)
  return parsed
}

const enumValue = <T extends readonly string[]>(value: unknown, allowed: T, field: string): T[number] => {
  if (typeof value !== 'string' || !allowed.includes(value)) throw new Error(`Invalid clearing batch ${field}`)
  return value as T[number]
}

export function parseClearingBatches(raw: unknown): ClearingBatch[] {
  if (!Array.isArray(raw)) throw new Error('Invalid clearing batches response')
  return raw.map((value) => {
    if (!isRecord(value)) throw new Error('Invalid clearing batch')
    const itemCount = finiteNumber(value.itemCount, 'itemCount')
    if (!Number.isInteger(itemCount) || itemCount < 0) throw new Error('Invalid clearing batch itemCount')
    return {
      id: requiredString(value.id, 'id'), batchReference: requiredString(value.batchReference, 'batchReference'),
      rail: enumValue(value.rail, PAYMENT_RAILS, 'rail'),
      settlementType: enumValue(value.settlementType, SETTLEMENT_TYPES, 'settlementType'),
      status: enumValue(value.status, CLEARING_STATUSES, 'status'),
      totalDebit: finiteNumber(value.totalDebit, 'totalDebit'), totalCredit: finiteNumber(value.totalCredit, 'totalCredit'),
      netPosition: finiteNumber(value.netPosition, 'netPosition'),
      currency: requiredString(value.currency, 'currency').toUpperCase(), itemCount,
      cycleId: optionalString(value.cycleId, 'cycleId'), settlementDate: optionalString(value.settlementDate, 'settlementDate'),
      settledAt: optionalString(value.settledAt, 'settledAt'), createdAt: requiredString(value.createdAt, 'createdAt'),
      updatedAt: requiredString(value.updatedAt, 'updatedAt'),
    }
  })
}

export function formatClearingMoney(value: number, currency: string, locale: string): string {
  try {
    return new Intl.NumberFormat(locale, { style: 'currency', currency, currencyDisplay: 'code' }).format(value)
  } catch {
    return `${value.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`
  }
}
