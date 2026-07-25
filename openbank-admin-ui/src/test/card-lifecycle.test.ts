// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The Cards screen only renders buttons for transitions the card-issuance
// aggregate would actually accept — offering an "Activate" on an ACTIVE card just
// buys the operator a 400. This test pins the table against Card.kt's guards
// (openbank-card-issuance-service/src/main/kotlin/.../domain/model/Card.kt), so a
// backend change that isn't mirrored here fails loudly instead of shipping dead
// buttons.

import { describe, it, expect } from 'vitest'
import { CARD_STATUSES, legalTransitions, isTerminal, type CardStatus } from '@/lib/cards/lifecycle'

const actionsFrom = (s: CardStatus) => legalTransitions(s).map(tr => tr.action).sort()

describe('card lifecycle transition table', () => {
  it('mirrors Card.kt: activate() only from PENDING', () => {
    expect(actionsFrom('PENDING')).toEqual(['activate', 'cancel'])
  })

  it('mirrors Card.kt: suspend() only from ACTIVE, resume() only from SUSPENDED', () => {
    expect(actionsFrom('ACTIVE')).toEqual(['block', 'cancel', 'suspend'])
    expect(actionsFrom('SUSPENDED')).toEqual(['block', 'cancel', 'resume'])
  })

  it('mirrors Card.CANCELLABLE_STATUSES: BLOCKED may only be cancelled', () => {
    expect(actionsFrom('BLOCKED')).toEqual(['cancel'])
  })

  it('mirrors Card.TERMINAL_STATUSES: EXPIRED and CANCELLED offer nothing', () => {
    expect(legalTransitions('EXPIRED')).toEqual([])
    expect(legalTransitions('CANCELLED')).toEqual([])
    expect(isTerminal('EXPIRED')).toBe(true)
    expect(isTerminal('CANCELLED')).toBe(true)
    expect(isTerminal('BLOCKED')).toBe(false)
  })

  it('marks block/cancel irreversible and reason-bearing, the rest not', () => {
    for (const status of CARD_STATUSES) {
      for (const tr of legalTransitions(status)) {
        const irreversible = tr.action === 'block' || tr.action === 'cancel'
        expect(tr.irreversible, `${status}/${tr.action}`).toBe(irreversible)
        expect(tr.reason, `${status}/${tr.action}`).toBe(irreversible)
      }
    }
  })

  it('offers nothing for an unknown or missing status', () => {
    expect(legalTransitions('WHATEVER')).toEqual([])
    expect(legalTransitions(undefined)).toEqual([])
  })
})
