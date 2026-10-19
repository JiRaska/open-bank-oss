// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure rules of the payments page: who may be offered "release", and the netting groups.
import type { Payment } from './types'

/**
 * Release is offered only to a payment releaser who is a person (not an AI principal) and is not
 * the creator of the deal(s) behind the payment. A courtesy — the service refuses all three.
 */
export function canReleasePayment(p: Pick<Payment, 'status' | 'createdBy' | 'dealCreators'>, who: { actor: string | null; holdsRelease: boolean; ai: boolean }): boolean {
  if (!who.holdsRelease || who.ai || p.status !== 'PROPOSED') return false
  if (who.actor === null) return true
  return p.createdBy !== who.actor && !(p.dealCreators ?? []).includes(who.actor)
}

export type NettingGroup = { key: string; counterparty: string; valueDate: string; currency: string; count: number; net: number }

/** Payments of one counterparty, value date and currency are netted: OUT counts negative, IN positive. */
export function nettingGroups(payments: Payment[]): NettingGroup[] {
  const map = new Map<string, NettingGroup>()
  for (const p of payments) {
    if (p.status === 'CANCELLED' || p.status === 'FAILED') continue
    const key = `${p.counterpartyId}|${p.valueDate}|${p.currency}`
    const g = map.get(key) ?? { key, counterparty: p.counterpartyName ?? p.counterpartyId, valueDate: p.valueDate, currency: p.currency, count: 0, net: 0 }
    g.count += 1
    g.net = Math.round((g.net + (p.direction === 'IN' ? p.amount : -p.amount)) * 100) / 100
    map.set(key, g)
  }
  return [...map.values()].filter(g => g.count > 1)
}
