// SPDX-License-Identifier: Apache-2.0

import { CARD_NETWORKS, CARD_TYPES, type AccountRef, type Card, type CardEntitlements, type PartyRef } from './types'
import { CARD_STATUSES } from './lifecycle'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const CURRENCY = /^[A-Z]{3}$/
const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/
const DISPLAY_EXPIRY = /^(0[1-9]|1[0-2])\/\d{2}$/
const MASKED_PAN = /^[0-9*\u2022 -]+$/

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalid object')
  return value as Record<string, unknown>
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) throw new Error(`invalid ${field}`)
  return value
}

function uuid(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!UUID.test(parsed)) throw new Error(`invalid ${field}`)
  return parsed
}

function oneOf<const T extends readonly string[]>(value: unknown, allowed: T, field: string): T[number] {
  const parsed = text(value, field)
  if (!allowed.includes(parsed)) throw new Error(`invalid ${field}`)
  return parsed as T[number]
}

function nonNegativeInteger(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`invalid ${field}`)
  return value
}

function finiteNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`invalid ${field}`)
  return value
}

function bool(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') throw new Error(`invalid ${field}`)
  return value
}

function optionalText(value: unknown, field: string): string | null | undefined {
  if (value === null || value === undefined) return value
  return text(value, field)
}

function optionalBool(value: unknown, field: string): boolean | undefined {
  if (value === undefined) return undefined
  return bool(value, field)
}

function instant(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!Number.isFinite(Date.parse(parsed))) throw new Error(`invalid ${field}`)
  return parsed
}

function optionalInstant(value: unknown, field: string): string | null | undefined {
  if (value === null || value === undefined) return value
  return instant(value, field)
}

function currency(value: unknown, field: string): string {
  const parsed = text(value, field)
  if (!CURRENCY.test(parsed)) throw new Error(`invalid ${field}`)
  return parsed
}

export function parseCard(value: unknown): Card {
  const raw = record(value)
  const maskedPan = text(raw.maskedPan, 'maskedPan')
  const maskCount = [...maskedPan].filter(char => char === '*' || char === '\u2022').length
  if (!MASKED_PAN.test(maskedPan) || maskCount < 4 || !/\d{4}$/.test(maskedPan)) {
    throw new Error('invalid maskedPan')
  }

  const expiryDate = text(raw.expiryDate, 'expiryDate')
  if (!DISPLAY_EXPIRY.test(expiryDate) && !LOCAL_DATE.test(expiryDate)) throw new Error('invalid expiryDate')

  const dailyLimitMinorUnits = nonNegativeInteger(raw.dailyLimitMinorUnits, 'dailyLimitMinorUnits')
  const monthlyLimitMinorUnits = nonNegativeInteger(raw.monthlyLimitMinorUnits, 'monthlyLimitMinorUnits')
  if (dailyLimitMinorUnits > monthlyLimitMinorUnits) throw new Error('daily limit exceeds monthly limit')

  // Constructing a new allow-listed object is the client-side PCI boundary: even if an
  // upstream regression adds PAN/CVV-shaped fields, the admin console never retains them.
  return {
    id: uuid(raw.id, 'id'),
    partyId: uuid(raw.partyId, 'partyId'),
    accountId: uuid(raw.accountId, 'accountId'),
    productCode: text(raw.productCode, 'productCode'),
    cardType: oneOf(raw.cardType, CARD_TYPES, 'cardType'),
    network: oneOf(raw.network, CARD_NETWORKS, 'network'),
    maskedPan,
    cardholderName: text(raw.cardholderName, 'cardholderName'),
    embossedName: text(raw.embossedName, 'embossedName'),
    expiryDate,
    status: oneOf(raw.status, CARD_STATUSES, 'status'),
    dailyLimitMinorUnits,
    monthlyLimitMinorUnits,
    currency: currency(raw.currency, 'currency'),
    deliveryAddress: optionalText(raw.deliveryAddress, 'deliveryAddress'),
    activatedAt: optionalInstant(raw.activatedAt, 'activatedAt'),
    blockedAt: optionalInstant(raw.blockedAt, 'blockedAt'),
    blockedReason: optionalText(raw.blockedReason, 'blockedReason'),
    createdAt: instant(raw.createdAt, 'createdAt'),
    updatedAt: optionalInstant(raw.updatedAt, 'updatedAt'),
    contactlessEnabled: optionalBool(raw.contactlessEnabled, 'contactlessEnabled'),
    onlineEnabled: optionalBool(raw.onlineEnabled, 'onlineEnabled'),
    atmEnabled: optionalBool(raw.atmEnabled, 'atmEnabled'),
    abroadEnabled: optionalBool(raw.abroadEnabled, 'abroadEnabled'),
  }
}

export function parseCardList(value: unknown): Card[] {
  if (!Array.isArray(value)) throw new Error('invalid card list')
  return value.map(parseCard)
}

export function parsePartyRef(value: unknown): PartyRef {
  const raw = record(value)
  return {
    id: uuid(raw.id, 'id'),
    legalName: optionalText(raw.legalName, 'legalName'),
    tradingName: optionalText(raw.tradingName, 'tradingName'),
    email: optionalText(raw.email, 'email'),
    status: optionalText(raw.status, 'status'),
    kycStatus: optionalText(raw.kycStatus, 'kycStatus'),
    partyType: optionalText(raw.partyType, 'partyType'),
  }
}

export function parseAccountRef(value: unknown): AccountRef {
  const raw = record(value)
  return {
    id: uuid(raw.id, 'id'),
    accountNumber: text(raw.accountNumber, 'accountNumber'),
    accountType: text(raw.accountType, 'accountType'),
    partyId: uuid(raw.partyId, 'partyId'),
    productId: uuid(raw.productId, 'productId'),
    currencyCode: currency(raw.currencyCode, 'currencyCode'),
    status: text(raw.status, 'status'),
  }
}

export function parseCardEntitlements(value: unknown): CardEntitlements {
  const raw = record(value)
  if (!Array.isArray(raw.networks) || !Array.isArray(raw.tiers)) throw new Error('invalid entitlement lists')
  const maxCards = Number(raw.maxCards)
  const remaining = Number(raw.remaining)
  if (!Number.isSafeInteger(maxCards) || maxCards < -1 || !Number.isSafeInteger(remaining) || remaining < -1) {
    throw new Error('invalid entitlement quota')
  }
  return {
    productCode: text(raw.productCode, 'productCode'),
    maxCards,
    issued: nonNegativeInteger(raw.issued, 'issued'),
    remaining,
    virtualCardAllowed: bool(raw.virtualCardAllowed, 'virtualCardAllowed'),
    singleUseAllowed: bool(raw.singleUseAllowed, 'singleUseAllowed'),
    networks: raw.networks.map(network => oneOf(network, CARD_NETWORKS, 'network')),
    tiers: raw.tiers.map(tier => text(tier, 'tier')),
    monthlyFeePerCard: finiteNumber(raw.monthlyFeePerCard, 'monthlyFeePerCard'),
    enabled: bool(raw.enabled, 'enabled'),
    source: oneOf(raw.source, ['CATALOG', 'FALLBACK'] as const, 'source'),
  }
}
