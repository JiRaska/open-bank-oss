// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { isUuid } from './resolveParty'

export interface PartyListItem {
  id: string
  partyType: string
  status: string
  legalName: string
  tradingName?: string
  email: string
  kycStatus: string
  createdAt: string
}

export interface PartyListPage {
  items: PartyListItem[]
  total: number
  page: number
  size: number
}

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function parseItem(raw: unknown): PartyListItem | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const item = raw as Record<string, unknown>
  if (
    !nonEmpty(item.id) || !isUuid(item.id) ||
    !nonEmpty(item.partyType) || !nonEmpty(item.status) ||
    !nonEmpty(item.legalName) || !nonEmpty(item.email) ||
    !nonEmpty(item.kycStatus) || !nonEmpty(item.createdAt) ||
    !Number.isFinite(Date.parse(item.createdAt))
  ) return null
  if (item.tradingName != null && typeof item.tradingName !== 'string') return null
  return {
    id: item.id,
    partyType: item.partyType,
    status: item.status,
    legalName: item.legalName,
    tradingName: item.tradingName || undefined,
    email: item.email,
    kycStatus: item.kycStatus,
    createdAt: item.createdAt,
  }
}

export function parsePartyListPage(raw: unknown, expectedPage: number): PartyListPage | null {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null
  const page = raw as Record<string, unknown>
  if (
    !Array.isArray(page.items) ||
    !Number.isInteger(page.total) || (page.total as number) < 0 ||
    page.page !== expectedPage ||
    !Number.isInteger(page.size) || (page.size as number) < 1 || (page.size as number) > 100 ||
    page.items.length > (page.size as number)
  ) return null
  const items = page.items.map(parseItem)
  if (items.some(item => item === null) || (page.total as number) < items.length) return null
  const validItems = items as PartyListItem[]
  if (new Set(validItems.map(item => item.id)).size !== validItems.length) return null
  return { items: validItems, total: page.total as number, page: expectedPage, size: page.size as number }
}
