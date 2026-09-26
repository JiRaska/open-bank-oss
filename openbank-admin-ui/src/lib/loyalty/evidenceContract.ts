// SPDX-License-Identifier: Apache-2.0

import type { LoyaltyCatalogueResponse, LoyaltyState } from '@/app/api/loyalty/route'
import type { LeafLedgerRow, LoyaltyPartyResponse } from '@/app/api/loyalty/party/[partyId]/route'

const STATES = new Set<LoyaltyState>(['ok', 'not_deployed', 'unreachable', 'unauthorized'])
const ENTRY_TYPES = new Set<LeafLedgerRow['type']>(['EARN', 'BURN', 'EXPIRE', 'REVERSE'])

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid loyalty evidence')
  return value as Record<string, unknown>
}

function state(value: unknown): LoyaltyState {
  if (typeof value !== 'string' || !STATES.has(value as LoyaltyState)) throw new Error('Invalid loyalty state')
  return value as LoyaltyState
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid ${field}`)
  return value
}

function count(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new Error(`Invalid ${field}`)
  return value
}

function instant(value: unknown, field: string, nullable = false): string | null {
  if (nullable && value === null) return null
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value))) throw new Error(`Invalid ${field}`)
  return value
}

export function parseLoyaltyCatalogue(raw: unknown): LoyaltyCatalogueResponse {
  const body = record(raw)
  const parsedState = state(body.state)
  if (parsedState !== 'ok') return { state: parsedState, benefits: [], earnSources: [], provisioning: null }
  if (!Array.isArray(body.benefits) || !Array.isArray(body.earnSources) || body.provisioning === null) {
    throw new Error('Incomplete loyalty catalogue')
  }
  const benefits = body.benefits.map(item => {
    const row = record(item)
    return {
      id: text(row.id, 'benefit id'),
      engine: text(row.engine, 'benefit engine'),
      priceLeaves: count(row.priceLeaves, 'benefit price'),
      validityDays: count(row.validityDays, 'benefit validity'),
      description: text(row.description, 'benefit description'),
    }
  })
  const earnSources = body.earnSources.map(item => {
    const row = record(item)
    return {
      id: text(row.id, 'earn source id'),
      leaves: count(row.leaves, 'earn amount'),
      validityDays: count(row.validityDays, 'earn validity'),
    }
  })
  const provisioning = record(body.provisioning)
  return {
    state: 'ok',
    benefits,
    earnSources,
    provisioning: {
      at: instant(provisioning.at, 'provisioning timestamp') as string,
      outstandingLeaves: count(provisioning.outstandingLeaves, 'outstanding balance'),
      annualCapPerParty: count(provisioning.annualCapPerParty, 'annual cap'),
      ruleVersion: text(provisioning.ruleVersion, 'rule version'),
    },
  }
}

export function parseLoyaltyParty(raw: unknown, expectedPartyId: string): LoyaltyPartyResponse {
  const body = record(raw)
  const parsedState = state(body.state)
  if (parsedState !== 'ok') {
    return { state: parsedState, partyId: expectedPartyId, balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] }
  }
  if (body.partyId !== expectedPartyId || !Array.isArray(body.history)) throw new Error('Mismatched loyalty customer')
  const history = body.history.map(item => {
    const row = record(item)
    if (typeof row.type !== 'string' || !ENTRY_TYPES.has(row.type as LeafLedgerRow['type'])) throw new Error('Invalid ledger type')
    return {
      id: text(row.id, 'ledger id'),
      type: row.type as LeafLedgerRow['type'],
      leaves: count(row.leaves, 'ledger amount'),
      remainingLeaves: count(row.remainingLeaves, 'remaining balance'),
      earnSourceId: row.earnSourceId === null ? null : text(row.earnSourceId, 'earn source id'),
      benefitId: row.benefitId === null ? null : text(row.benefitId, 'benefit id'),
      ruleVersion: text(row.ruleVersion, 'rule version'),
      occurredAt: instant(row.occurredAt, 'ledger timestamp') as string,
      expiresAt: instant(row.expiresAt, 'expiry timestamp', true),
    }
  })
  return {
    state: 'ok',
    partyId: expectedPartyId,
    balance: count(body.balance, 'balance'),
    earnedThisYear: count(body.earnedThisYear, 'year earnings'),
    earnedTotal: count(body.earnedTotal, 'total earnings'),
    nextExpiry: instant(body.nextExpiry, 'next expiry', true),
    history,
  }
}
