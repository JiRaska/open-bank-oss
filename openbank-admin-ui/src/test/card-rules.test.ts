// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pins the limits/controls invariants against Card.kt, the same way
// card-lifecycle.test.ts pins the transition table: a backend guard that stops
// being mirrored here starts producing bare 400s in the operator's face.
//
// Card.kt:
//   withLimits()   require(status in setOf(ACTIVE, SUSPENDED, PENDING))
//                  require(dailyMinor >= 0 && monthlyMinor >= 0)
//                  require(dailyMinor <= monthlyMinor)
//   withControls() require(status in setOf(ACTIVE, SUSPENDED, PENDING))

import { describe, it, expect } from 'vitest'
import { CARD_STATUSES } from '@/lib/cards/lifecycle'
import { canEditSettings, settingsBlock, validateLimits, limitsAcceptable } from '@/lib/cards/rules'

describe('which cards may be re-limited / re-controlled', () => {
  it('mirrors Card.withLimits: PENDING, ACTIVE and SUSPENDED only', () => {
    const editable = CARD_STATUSES.filter(canEditSettings)
    expect(editable).toEqual(['PENDING', 'ACTIVE', 'SUSPENDED'])
  })

  it('names the reason a dead card cannot be edited, instead of just refusing', () => {
    expect(settingsBlock('ACTIVE')).toBeNull()
    expect(settingsBlock('BLOCKED')).toBe('blocked')
    expect(settingsBlock('CANCELLED')).toBe('terminal')
    expect(settingsBlock('EXPIRED')).toBe('terminal')
    expect(settingsBlock('WHATEVER')).toBe('unknown_status')
    expect(settingsBlock(undefined)).toBe('unknown_status')
  })
})

describe('limit validation', () => {
  const ok = { status: 'ACTIVE', dailyMinorUnits: 100, monthlyMinorUnits: 1000 }

  it('accepts a well-formed edit on a live card', () => {
    expect(validateLimits(ok)).toEqual([])
    expect(limitsAcceptable(ok)).toBe(true)
  })

  it('accepts daily == monthly (the guard is <=, not <)', () => {
    expect(validateLimits({ ...ok, dailyMinorUnits: 1000 })).toEqual([])
  })

  it('rejects daily > monthly', () => {
    expect(validateLimits({ ...ok, dailyMinorUnits: 1001 })).toEqual(['daily_exceeds_monthly'])
  })

  it('rejects a field that did not parse, and never calls it a comparison failure', () => {
    expect(validateLimits({ ...ok, dailyMinorUnits: null })).toEqual(['daily_not_a_number'])
    expect(validateLimits({ ...ok, monthlyMinorUnits: null })).toEqual(['monthly_not_a_number'])
  })

  it('rejects a negative value (Card.withLimits requires non-negative)', () => {
    expect(validateLimits({ ...ok, dailyMinorUnits: -1 })).toEqual(['daily_not_a_number'])
    expect(validateLimits({ ...ok, monthlyMinorUnits: -1 })).toEqual(['monthly_not_a_number'])
  })

  it('reports the status violation as well as the value ones', () => {
    expect(validateLimits({ status: 'BLOCKED', dailyMinorUnits: 2000, monthlyMinorUnits: 1000 }))
      .toEqual(['status', 'daily_exceeds_monthly'])
  })

  it('accepts zero limits — a live card capped to nothing is legal, not an error', () => {
    expect(validateLimits({ status: 'PENDING', dailyMinorUnits: 0, monthlyMinorUnits: 0 })).toEqual([])
  })
})
