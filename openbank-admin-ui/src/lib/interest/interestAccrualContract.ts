export const ACCRUAL_STATUSES = ['ACCRUING', 'CAPITALIZING', 'CAPITALIZED', 'REVERSED', 'SUSPENDED'] as const
export const DAY_COUNTS = ['ACT_365', 'ACT_360', 'ACT_ACT', 'THIRTY_360'] as const

export interface AccrualRecord {
  id: string; accountId: string; accrualDate: string; accruedAmount: number
  currency: string; rate: number; dayCount: (typeof DAY_COUNTS)[number]
  status: (typeof ACCRUAL_STATUSES)[number]
}

const string = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid interest accrual ${field}`)
  return value
}
const number = (value: unknown, field: string): number => {
  const parsed = typeof value === 'number' ? value : typeof value === 'string' && value.trim() !== '' ? Number(value) : NaN
  if (!Number.isFinite(parsed)) throw new Error(`Invalid interest accrual ${field}`)
  return parsed
}
const enumValue = <T extends readonly string[]>(value: unknown, allowed: T, field: string): T[number] => {
  if (typeof value !== 'string' || !allowed.includes(value)) throw new Error(`Invalid interest accrual ${field}`)
  return value as T[number]
}

export function parseInterestAccruals(raw: unknown): AccrualRecord[] {
  if (!Array.isArray(raw)) throw new Error('Invalid interest accrual response')
  return raw.map((value) => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid interest accrual')
    const item = value as Record<string, unknown>
    const accrualDate = string(item.accrualDate, 'accrualDate')
    if (!/^\d{4}-\d{2}-\d{2}$/.test(accrualDate)) throw new Error('Invalid interest accrual accrualDate')
    return {
      id: string(item.id, 'id'), accountId: string(item.accountId, 'accountId'), accrualDate,
      accruedAmount: number(item.accruedAmount, 'accruedAmount'), currency: string(item.currency, 'currency').toUpperCase(),
      rate: number(item.rate, 'rate'), dayCount: enumValue(item.dayCount, DAY_COUNTS, 'dayCount'),
      status: enumValue(item.status, ACCRUAL_STATUSES, 'status'),
    }
  })
}

export function statusTone(status: AccrualRecord['status']): 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'CAPITALIZED') return 'success'
  if (status === 'REVERSED') return 'danger'
  if (status === 'ACCRUING' || status === 'CAPITALIZING') return 'warning'
  return 'neutral'
}
