// SPDX-License-Identifier: Apache-2.0

import type { Account, AccountStatus, AccountType } from '@/types'

export const CUSTOMER_ACCOUNT_TYPES = ['CURRENT', 'SAVINGS', 'TERM_DEPOSIT'] as const
const CUSTOMER_ACCOUNT_TYPE_SET: ReadonlySet<string> = new Set(CUSTOMER_ACCOUNT_TYPES)
const ACCOUNT_STATUSES: ReadonlySet<string> = new Set<AccountStatus>([
  'PENDING_ACTIVATION', 'ACTIVE', 'DORMANT', 'FROZEN', 'CLOSED',
])
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const CURRENCY = /^[A-Z]{3}$/

export class AccountOpeningContractError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AccountOpeningContractError'
  }
}

export interface AccountOpeningProduct {
  id: string
  code: string
  name: string
  type: typeof CUSTOMER_ACCOUNT_TYPES[number]
  currency: string
  status: 'ACTIVE'
  terms: AccountOpeningTerms | null
}

export interface AccountOpeningTerms {
  version: string
  url: string
  effectiveFrom: string
}

export interface ExpectedOpenedAccount {
  partyId: string
  productId: string
  accountType: string
  currencyCode: string
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new AccountOpeningContractError('invalid object')
  return value as Record<string, unknown>
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) throw new AccountOpeningContractError(`invalid ${field}`)
  return value
}

function uuid(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!UUID.test(parsed)) throw new AccountOpeningContractError(`invalid ${field}`)
  return parsed
}

function currency(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!CURRENCY.test(parsed)) throw new AccountOpeningContractError(`invalid ${field}`)
  return parsed
}

function instant(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!Number.isFinite(Date.parse(parsed))) throw new AccountOpeningContractError(`invalid ${field}`)
  return parsed
}

function localDate(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(parsed) || !Number.isFinite(Date.parse(`${parsed}T00:00:00Z`))) {
    throw new AccountOpeningContractError(`invalid ${field}`)
  }
  return parsed
}

function webUrl(value: unknown, field: string): string {
  const parsed = text(value, field)
  let url: URL
  try {
    url = new URL(parsed)
  } catch {
    throw new AccountOpeningContractError(`invalid ${field}`)
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new AccountOpeningContractError(`invalid ${field}`)
  }
  return parsed
}

function currentTerms(value: unknown, today: string): AccountOpeningTerms | null {
  if (!Array.isArray(value)) throw new AccountOpeningContractError('invalid termsAndConditions')
  const candidates = value.map(item => {
    const raw = record(item)
    const effectiveFrom = localDate(raw.effectiveFrom, 'terms effectiveFrom')
    const effectiveTo = raw.effectiveTo === null || raw.effectiveTo === undefined
      ? null
      : localDate(raw.effectiveTo, 'terms effectiveTo')
    return {
      version: text(raw.version, 'terms version'),
      url: webUrl(raw.url, 'terms url'),
      effectiveFrom,
      effectiveTo,
    }
  }).filter(terms => terms.effectiveFrom <= today && (terms.effectiveTo === null || terms.effectiveTo >= today))
    .sort((left, right) => right.effectiveFrom.localeCompare(left.effectiveFrom))
  const selected = candidates[0]
  return selected ? {
    version: selected.version,
    url: selected.url,
    effectiveFrom: selected.effectiveFrom,
  } : null
}

function parseProduct(value: unknown, today: string): AccountOpeningProduct {
  const raw = record(value)
  const type = text(raw.type, 'type')
  if (!CUSTOMER_ACCOUNT_TYPE_SET.has(type)) throw new AccountOpeningContractError('invalid customer account type')
  if (raw.status !== 'ACTIVE') throw new AccountOpeningContractError('inactive product in active catalogue')
  const terms = currentTerms(raw.termsAndConditions ?? [], today)
  if (type === 'TERM_DEPOSIT' && !terms) {
    throw new AccountOpeningContractError('term-deposit product has no current terms')
  }
  return {
    id: uuid(raw.id, 'id'),
    code: text(raw.code, 'code'),
    name: text(raw.name, 'name'),
    type: type as AccountOpeningProduct['type'],
    currency: currency(raw.currency, 'currency'),
    status: 'ACTIVE',
    terms,
  }
}

/** The v1 product-catalog list contract is a JSON array; invalid rows fail the whole choice set. */
export function parseAccountOpeningProducts(value: unknown, today = new Date()): AccountOpeningProduct[] {
  if (!Array.isArray(value)) throw new AccountOpeningContractError('invalid product catalogue')
  const todayIso = today.toISOString().slice(0, 10)
  return value.map(item => parseProduct(item, todayIso))
}

/**
 * Prove that a 2xx opening response describes the account the operator requested.
 * A mismatched or partial response is not success and must never drive a detail-page redirect.
 */
export function parseOpenedAccount(value: unknown, expected: ExpectedOpenedAccount): Account {
  const raw = record(value)
  const accountType = text(raw.accountType, 'accountType')
  const status = text(raw.status, 'status')
  if (!CUSTOMER_ACCOUNT_TYPE_SET.has(accountType)) throw new AccountOpeningContractError('invalid opened account type')
  if (!ACCOUNT_STATUSES.has(status)) throw new AccountOpeningContractError('invalid account status')

  const account: Account = {
    id: uuid(raw.id, 'id'),
    accountNumber: text(raw.accountNumber, 'accountNumber'),
    accountType: accountType as AccountType,
    partyId: uuid(raw.partyId, 'partyId'),
    productId: uuid(raw.productId, 'productId'),
    currencyCode: currency(raw.currencyCode, 'currencyCode'),
    status: status as AccountStatus,
    openedAt: instant(raw.openedAt, 'openedAt'),
    ...(raw.closedAt === null || raw.closedAt === undefined
      ? {}
      : { closedAt: instant(raw.closedAt, 'closedAt') }),
  }

  if (account.partyId !== expected.partyId
    || account.productId !== expected.productId
    || account.accountType !== expected.accountType
    || account.currencyCode !== expected.currencyCode) {
    throw new AccountOpeningContractError('opened account does not match the request')
  }
  return account
}
