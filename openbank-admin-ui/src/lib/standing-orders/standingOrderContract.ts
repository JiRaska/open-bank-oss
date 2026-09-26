export const STANDING_ORDER_STATUSES = ['ACTIVE', 'PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED'] as const
export const STANDING_ORDER_FREQUENCIES = ['ONCE', 'DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUALLY'] as const
export const STANDING_ORDER_PAYMENT_TYPES = ['SEPA_CREDIT', 'DOMESTIC', 'INTERNAL'] as const

export type StandingOrderStatus = typeof STANDING_ORDER_STATUSES[number]
export type StandingOrderFrequency = typeof STANDING_ORDER_FREQUENCIES[number]
export type StandingOrderPaymentType = typeof STANDING_ORDER_PAYMENT_TYPES[number]

export type StandingOrder = {
  id: string
  partyId: string
  debtorAccountId: string
  creditorIban: string
  creditorName: string
  status: StandingOrderStatus
  frequency: StandingOrderFrequency
  paymentType: StandingOrderPaymentType
  amountMinorUnits: number
  currency: string
  nextExecutionDate: string
  remittanceInfo?: string
  executionCount: number
  createdAt: string
  updatedAt: string
}

type JsonRecord = Record<string, unknown>

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: JsonRecord, key: string): string {
  const value = record[key]
  if (typeof value !== 'string' || value.length === 0) throw new Error(`invalid ${key}`)
  return value
}

function isLocalDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const parsed = new Date(Date.UTC(year, month - 1, day))
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day
}

export function parseStandingOrders(value: unknown): StandingOrder[] {
  if (!Array.isArray(value)) throw new Error('standing-order response is not a list')
  return value.map(item => {
    if (!isRecord(item)) throw new Error('invalid standing order')
    const status = requiredString(item, 'status')
    const frequency = requiredString(item, 'frequency')
    const paymentType = requiredString(item, 'paymentType')
    const currency = requiredString(item, 'currency')
    const nextExecutionDate = requiredString(item, 'nextExecutionDate')
    if (!STANDING_ORDER_STATUSES.includes(status as StandingOrderStatus)) throw new Error('invalid status')
    if (!STANDING_ORDER_FREQUENCIES.includes(frequency as StandingOrderFrequency)) throw new Error('invalid frequency')
    if (!STANDING_ORDER_PAYMENT_TYPES.includes(paymentType as StandingOrderPaymentType)) throw new Error('invalid payment type')
    if (!/^[A-Z]{3}$/.test(currency)) throw new Error('invalid currency')
    if (!isLocalDate(nextExecutionDate)) throw new Error('invalid next execution date')
    if (!Number.isSafeInteger(item.amountMinorUnits) || (item.amountMinorUnits as number) <= 0) throw new Error('invalid amount')
    if (!Number.isInteger(item.executionCount) || (item.executionCount as number) < 0) throw new Error('invalid execution count')
    const createdAt = requiredString(item, 'createdAt')
    const updatedAt = requiredString(item, 'updatedAt')
    if (!Number.isFinite(Date.parse(createdAt)) || !Number.isFinite(Date.parse(updatedAt))) throw new Error('invalid timestamp')
    return {
      id: requiredString(item, 'id'),
      partyId: requiredString(item, 'partyId'),
      debtorAccountId: requiredString(item, 'debtorAccountId'),
      creditorIban: requiredString(item, 'creditorIban'),
      creditorName: requiredString(item, 'creditorName'),
      status: status as StandingOrderStatus,
      frequency: frequency as StandingOrderFrequency,
      paymentType: paymentType as StandingOrderPaymentType,
      amountMinorUnits: item.amountMinorUnits as number,
      currency,
      nextExecutionDate,
      remittanceInfo: typeof item.remittanceInfo === 'string' && item.remittanceInfo.length > 0 ? item.remittanceInfo : undefined,
      executionCount: item.executionCount as number,
      createdAt,
      updatedAt,
    }
  })
}

export function formatMinorUnits(minorUnits: number, locale: string): string {
  return (minorUnits / 100).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function formatLocalDate(value: string, locale: string): string {
  const [year, month, day] = value.split('-').map(Number)
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' })
    .format(new Date(Date.UTC(year, month - 1, day)))
}
