// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Card Center — the chargeback desk (ADR-0283 phase 3, issue #8811).
//
// TWO PROPERTIES, both of which a naive screen loses:
//
//  1. An EXPIRED representment window must not render like an urgent one. `daysUntil` returns a
//     negative number and the desk shows "past", because clamping to zero makes a deadline the
//     bank has already missed look like one it can still meet — opposite work, same pixels.
//  2. Both vocabularies survive. The bank's status and the network's own string are shown side by
//     side and never merged: the scheme's vocabulary differs per network and moves with their
//     releases, so a screen showing only one cannot tell an unrecognised scheme state from a state
//     the bank decided.

import { describe, expect, it } from 'vitest'
import { daysUntil, formatMinorUnits } from '@/lib/cards/lifecycleTypes'

describe('card dispute deadlines', () => {
  const now = new Date('2026-09-05T09:00:00Z')

  it('returns a NEGATIVE number for a window that has closed, never zero', () => {
    // The discriminating case: 0 would render as "due today", which is the wrong instruction.
    expect(daysUntil('2026-09-01', now)).toBeLessThan(0)
    expect(daysUntil('2026-09-01', now)).toBe(-4)
  })

  it('counts the days remaining for an open window', () => {
    expect(daysUntil('2026-09-12', now)).toBe(7)
  })

  it('answers null when the network gave no deadline, rather than inventing one', () => {
    // A simulated or absent deadline is a different fact from "due today", and the desk says so.
    expect(daysUntil(null, now)).toBeNull()
    expect(daysUntil('not-a-date', now)).toBeNull()
  })

  it('formats minor units without the caller having to divide', () => {
    // The service speaks minor units end to end; only the screen formats, and only here.
    expect(formatMinorUnits(500_00, 'CZK', 'en-GB')).toContain('500')
    expect(formatMinorUnits(1, 'CZK', 'en-GB')).toContain('0.01')
  })
})
