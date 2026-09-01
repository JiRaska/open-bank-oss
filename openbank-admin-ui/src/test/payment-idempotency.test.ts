// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it, vi } from 'vitest'
import { clearPaymentAttempt, idempotencyKeyForPayload, type PaymentAttemptRef } from '@/lib/payments/idempotency'

describe('payment creation idempotency attempts', () => {
  it('reuses a key for an identical retry and rotates it for edited payloads', () => {
    const attempt: PaymentAttemptRef = { current: null }
    const mint = vi.fn().mockReturnValueOnce('key-1').mockReturnValueOnce('key-2')

    expect(idempotencyKeyForPayload(attempt, '{"amount":100}', mint)).toBe('key-1')
    expect(idempotencyKeyForPayload(attempt, '{"amount":100}', mint)).toBe('key-1')
    expect(idempotencyKeyForPayload(attempt, '{"amount":101}', mint)).toBe('key-2')
    expect(mint).toHaveBeenCalledTimes(2)
  })

  it('clears a successful attempt so the next payment gets a new key', () => {
    const attempt: PaymentAttemptRef = { current: null }
    const mint = vi.fn().mockReturnValueOnce('key-1').mockReturnValueOnce('key-2')

    expect(idempotencyKeyForPayload(attempt, 'same-body', mint)).toBe('key-1')
    clearPaymentAttempt(attempt)
    expect(idempotencyKeyForPayload(attempt, 'same-body', mint)).toBe('key-2')
  })
})
