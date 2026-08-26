// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface PaymentAttempt {
  payload: string
  key: string
}

export interface PaymentAttemptRef {
  current: PaymentAttempt | null
}

export function idempotencyKeyForPayload(
  attempt: PaymentAttemptRef,
  payload: string,
  mint: () => string,
): string {
  if (attempt.current?.payload === payload) return attempt.current.key
  const key = mint()
  attempt.current = { payload, key }
  return key
}

export function clearPaymentAttempt(attempt: PaymentAttemptRef): void {
  attempt.current = null
}
