export const PRODUCT_STATUSES = ['ACTIVE', 'INACTIVE', 'DRAFT', 'DEPRECATED', 'ARCHIVED'] as const

export interface WaiverRule {
  attribute: string
  operator: string
  threshold: string | null
  thresholdCurrency: string | null
  textValue: string | null
}

export interface FeeScheduleItem {
  id: string; code: string; name: string; type: string; amount: number; currency: string; frequency: string
  description: string | null; waivable: boolean; waiveCondition: string | null
  waiverEvaluable: boolean; waiverRule: WaiverRule | null
  productId: string; productCode: string; productName: string
  status: (typeof PRODUCT_STATUSES)[number]; updatedAt: string
}

export class FeeScheduleContractError extends Error {}

const record = (value: unknown, field: string): Record<string, unknown> => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new FeeScheduleContractError(`Invalid ${field}`)
  return value as Record<string, unknown>
}
const string = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim() === '') throw new FeeScheduleContractError(`Invalid ${field}`)
  return value
}
const optionalString = (value: unknown, field: string): string | null =>
  value === null || value === undefined ? null : string(value, field)
const boolean = (value: unknown, field: string): boolean => {
  if (typeof value !== 'boolean') throw new FeeScheduleContractError(`Invalid ${field}`)
  return value
}

function parseRule(value: unknown): WaiverRule | null {
  if (value === null || value === undefined) return null
  const rule = record(value, 'waiverRule')
  return {
    attribute: string(rule.attribute, 'waiverRule.attribute'), operator: string(rule.operator, 'waiverRule.operator'),
    threshold: optionalString(rule.threshold, 'waiverRule.threshold'),
    thresholdCurrency: optionalString(rule.thresholdCurrency, 'waiverRule.thresholdCurrency'),
    textValue: optionalString(rule.textValue, 'waiverRule.textValue'),
  }
}

export function parseFeeSchedule(raw: unknown): FeeScheduleItem[] {
  if (!Array.isArray(raw)) throw new FeeScheduleContractError('Invalid fee schedule response')
  return raw.map((value) => {
    const item = record(value, 'fee schedule item')
    const amount = typeof item.amount === 'number' ? item.amount : NaN
    if (!Number.isFinite(amount) || amount < 0) throw new FeeScheduleContractError('Invalid amount')
    const status = string(item.status, 'status')
    if (!PRODUCT_STATUSES.includes(status as (typeof PRODUCT_STATUSES)[number])) throw new FeeScheduleContractError('Invalid status')
    const waivable = boolean(item.waivable, 'waivable')
    const waiverEvaluable = boolean(item.waiverEvaluable, 'waiverEvaluable')
    const waiverRule = parseRule(item.waiverRule)
    if (waiverEvaluable !== (waiverRule !== null)) throw new FeeScheduleContractError('Inconsistent waiver rule')
    return {
      id: string(item.id, 'id'), code: string(item.code, 'code'), name: string(item.name, 'name'),
      type: string(item.type, 'type'), amount, currency: string(item.currency, 'currency').toUpperCase(),
      frequency: string(item.frequency, 'frequency'), description: optionalString(item.description, 'description'),
      waivable, waiveCondition: optionalString(item.waiveCondition, 'waiveCondition'), waiverEvaluable, waiverRule,
      productId: string(item.productId, 'productId'), productCode: string(item.productCode, 'productCode'),
      productName: string(item.productName, 'productName'), status: status as FeeScheduleItem['status'],
      updatedAt: string(item.updatedAt, 'updatedAt'),
    }
  })
}

export function describeWaiverRule(rule: WaiverRule): string {
  const target = rule.threshold === null ? rule.textValue : `${rule.threshold}${rule.thresholdCurrency ? ` ${rule.thresholdCurrency}` : ''}`
  return `${rule.attribute.replaceAll('_', ' ').toLowerCase()} ${rule.operator} ${target ?? '—'}`
}
