// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { approvalActionLabel, approvalDomainLabel, approvalExpiresAt } from '@/lib/approvals/presentation'

describe('approval presentation', () => {
  it('uses bank-language domain names instead of infrastructure slugs', () => {
    expect(approvalDomainLabel('domestic-payment', 'cs')).toBe('Tuzemské platby')
    expect(approvalDomainLabel('sepa-instant', 'en')).toBe('SEPA instant payments')
    expect(approvalDomainLabel('notification', 'cs')).toBe('Provozní zprávy')
  })

  it('explains high-risk policy actions in the selected language', () => {
    expect(approvalActionLabel('sanctions.clear', 'cs')).toBe('Rozhodnout sankční nález')
    expect(approvalActionLabel('transaction.sweep', 'en')).toBe('Sweep balance during party merge')
    expect(approvalActionLabel('opsmessage.compose', 'cs')).toBe('Odeslat provozní zprávu klientovi')
  })

  it('humanizes an unknown future action without hiding its technical key from the page', () => {
    expect(approvalActionLabel('futureDomain.reviewCase', 'en')).toBe('Review Case')
  })

  it('exposes the store-backed 24-hour expiry without inventing a client countdown', () => {
    expect(approvalExpiresAt('2026-08-26T08:15:00Z')?.toISOString()).toBe('2026-08-27T08:15:00.000Z')
    expect(approvalExpiresAt(null)).toBeNull()
    expect(approvalExpiresAt('not-a-timestamp')).toBeNull()
  })
})
