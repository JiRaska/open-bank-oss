// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import type { Account, AccountBalance, AccountStatus, AccountType } from '@/types'

const ACCOUNT_TYPES = new Set<AccountType>([
  'CURRENT', 'SAVINGS', 'TERM_DEPOSIT', 'NOSTRO',
  'GL_ASSET', 'GL_LIABILITY', 'GL_INCOME', 'GL_EXPENSE',
])
const ACCOUNT_STATUSES = new Set<AccountStatus>(['PENDING_ACTIVATION', 'ACTIVE', 'DORMANT', 'FROZEN', 'CLOSED'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isTimestamp(value: unknown): value is string {
  return isNonEmptyString(value) && Number.isFinite(Date.parse(value))
}

export function validateAccountDetail(value: unknown, requestedId: string): Account | null {
  if (!isRecord(value) || value.id !== requestedId) return null
  if (!isNonEmptyString(value.accountNumber) || !isNonEmptyString(value.partyId) || !isNonEmptyString(value.productId)) return null
  if (!isNonEmptyString(value.currencyCode) || !ACCOUNT_TYPES.has(value.accountType as AccountType)) return null
  if (!ACCOUNT_STATUSES.has(value.status as AccountStatus) || !isTimestamp(value.openedAt)) return null
  if (value.closedAt !== undefined && !isTimestamp(value.closedAt)) return null
  return value as unknown as Account
}

export function validateAccountBalance(
  value: unknown,
  account: Pick<Account, 'id' | 'currencyCode'>,
): AccountBalance | null {
  if (!isRecord(value) || value.accountId !== account.id || value.currencyCode !== account.currencyCode) return null
  if (!['availableBalance', 'currentBalance', 'reservedBalance', 'pendingBalance'].every(key =>
    typeof value[key] === 'number' && Number.isFinite(value[key]),
  )) return null
  if (!isTimestamp(value.lastUpdatedAt)) return null
  return value as unknown as AccountBalance
}
