// SPDX-License-Identifier: Apache-2.0

export type LoyaltyState = 'ok' | 'not_deployed' | 'unreachable' | 'unauthorized'

export interface LoyaltyBenefit {
  id: string
  engine: string
  priceLeaves: number
  validityDays: number
  description: string
}

export interface LoyaltyEarnSource {
  id: string
  leaves: number
  validityDays: number
}

export interface LoyaltyProvisioning {
  at: string
  outstandingLeaves: number
  annualCapPerParty: number
  ruleVersion: string
}

export interface LoyaltyCatalogueResponse {
  state: LoyaltyState
  benefits: LoyaltyBenefit[]
  earnSources: LoyaltyEarnSource[]
  provisioning: LoyaltyProvisioning | null
}

export interface LeafLedgerRow {
  id: string
  type: 'EARN' | 'BURN' | 'EXPIRE' | 'REVERSE'
  leaves: number
  remainingLeaves: number
  earnSourceId: string | null
  benefitId: string | null
  ruleVersion: string
  occurredAt: string
  expiresAt: string | null
}

export interface LoyaltyPartyResponse {
  state: LoyaltyState
  partyId: string
  balance: number
  earnedThisYear: number
  earnedTotal: number
  nextExpiry: string | null
  history: LeafLedgerRow[]
}
