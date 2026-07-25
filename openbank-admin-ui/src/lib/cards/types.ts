// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The card-issuance read model, mirrored from the service that owns it.
//
// Source of truth: openbank-card-issuance-service
//   - infrastructure/rest/dto/CardDtos.kt — CardResponse / CardEntitlementsResponse
//   - domain/model/Card.kt                — CardType / CardNetwork enums
//
// PCI: `maskedPan` is the ONLY pan-shaped field this console ever holds. The
// service also exposes GET /{id}/secure-details (full synthetic PAN + CVV for a
// virtual card); it is deliberately NOT modelled here and must never be — see
// the PCI note on the card detail page.

import type { CardStatus } from './lifecycle'

export const CARD_TYPES = ['DEBIT', 'CREDIT', 'PREPAID', 'VIRTUAL', 'SINGLE_USE'] as const
export type CardType = (typeof CARD_TYPES)[number]

export const CARD_NETWORKS = ['VISA', 'MASTERCARD', 'AMEX', 'UNIONPAY'] as const
export type CardNetwork = (typeof CARD_NETWORKS)[number]

/** Card types with no plastic — Card.VIRTUAL_FORM_TYPES. */
export const VIRTUAL_FORM_TYPES: readonly CardType[] = ['VIRTUAL', 'SINGLE_USE']

/** CardResponse. Every field the service returns; nothing derived, nothing invented. */
export interface Card {
  id: string
  partyId: string
  accountId: string
  productCode: string
  cardType: CardType | string
  network: CardNetwork | string
  maskedPan: string
  cardholderName: string
  embossedName: string
  expiryDate: string
  status: CardStatus | string
  dailyLimitMinorUnits: number
  monthlyLimitMinorUnits: number
  currency: string
  deliveryAddress?: string | null
  activatedAt?: string | null
  blockedAt?: string | null
  blockedReason?: string | null
  createdAt: string
  updatedAt?: string | null
  contactlessEnabled?: boolean
  onlineEnabled?: boolean
  atmEnabled?: boolean
  abroadEnabled?: boolean
}

/** The four channel controls, as PUT /{id}/controls takes them (all four, always). */
export interface CardControls {
  contactlessEnabled: boolean
  onlineEnabled: boolean
  atmEnabled: boolean
  abroadEnabled: boolean
}

/** Read the card's controls with the service's own defaults (all-on) for older rows. */
export function controlsOf(card: Pick<Card, 'contactlessEnabled' | 'onlineEnabled' | 'atmEnabled' | 'abroadEnabled'>): CardControls {
  return {
    contactlessEnabled: card.contactlessEnabled ?? true,
    onlineEnabled: card.onlineEnabled ?? true,
    atmEnabled: card.atmEnabled ?? true,
    abroadEnabled: card.abroadEnabled ?? true,
  }
}

/** CardEntitlementsResponse — what a party may still issue on a product. */
export interface CardEntitlements {
  productCode: string
  maxCards: number
  issued: number
  remaining: number
  virtualCardAllowed: boolean
  singleUseAllowed: boolean
  networks: (CardNetwork | string)[]
  tiers: string[]
  monthlyFeePerCard: number
  enabled: boolean
  source: 'CATALOG' | 'FALLBACK' | string
}

/** A party as party-service's data-minimised `toSimpleResponse()` returns it. */
export interface PartyRef {
  id: string
  legalName?: string | null
  tradingName?: string | null
  email?: string | null
  status?: string | null
  kycStatus?: string | null
  partyType?: string | null
}

/** An account as account-service's AccountResponse returns it. */
export interface AccountRef {
  id: string
  accountNumber: string
  accountType: string
  partyId: string
  productId: string
  currencyCode: string
  status: string
}
