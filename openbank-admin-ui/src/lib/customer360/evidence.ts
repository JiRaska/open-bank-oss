// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface DomainSummary {
  aggregateType: string
  events: number
  lastEventType: string
  lastOccurredAt: string
}

export interface Customer360Evidence {
  available: boolean
  partyId: string
  asOf: string | null
  domains: DomainSummary[]
  accountIds: string[]
  consents: { consentId: string; status: string; scopes: string[] }[]
  excludedCount: number
  error?: string
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

const isString = (value: unknown): value is string =>
  typeof value === 'string' && value.trim().length > 0

const isTimestamp = (value: unknown): value is string =>
  isString(value) && !Number.isNaN(Date.parse(value.replace(' ', 'T') + (value.includes('Z') || /[+-]\d\d:\d\d$/.test(value) ? '' : 'Z')))

/** Defense-in-depth for the browser boundary; the BFF remains the owning normalizer. */
export function parseCustomer360Evidence(raw: unknown, expectedPartyId: string): Customer360Evidence | null {
  if (!isRecord(raw) || typeof raw.available !== 'boolean' || raw.partyId !== expectedPartyId) return null
  if (!(raw.asOf === null || isTimestamp(raw.asOf))) return null
  if (!Array.isArray(raw.domains) || !Array.isArray(raw.accountIds) || !Array.isArray(raw.consents)) return null
  if (!Number.isSafeInteger(raw.excludedCount) || (raw.excludedCount as number) < 0) return null

  const domains = raw.domains.filter((value): value is DomainSummary => isRecord(value)
    && isString(value.aggregateType)
    && Number.isSafeInteger(value.events) && (value.events as number) > 0
    && isString(value.lastEventType)
    && isTimestamp(value.lastOccurredAt))
  const accountIds = raw.accountIds.filter(isString)
  const consents = raw.consents.filter((value): value is Customer360Evidence['consents'][number] => isRecord(value)
    && isString(value.consentId)
    && isString(value.status)
    && Array.isArray(value.scopes)
    && value.scopes.every(isString))
  if (domains.length !== raw.domains.length || accountIds.length !== raw.accountIds.length || consents.length !== raw.consents.length) return null

  const error = raw.error === undefined ? undefined : isString(raw.error) ? raw.error : null
  if (error === null) return null
  return {
    available: raw.available,
    partyId: expectedPartyId,
    asOf: raw.asOf,
    domains,
    accountIds,
    consents,
    excludedCount: raw.excludedCount as number,
    ...(error ? { error } : {}),
  }
}
