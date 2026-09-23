// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** lending-service serialises the domain `CurrencyCode` as `{ code, defaultFractionDigits }` on its
 *  per-loan and per-application payloads, while its summaries (and its openapi.yaml) use a plain
 *  ISO string. Both shapes reach the console, so compare and render the CODE, never the value. */
export type WireCurrency = string | { code?: unknown }

export type WireMoney = { amount: number; currency: WireCurrency }

/** Same rendering as the credit-risk page (`150 000 Kč` in Czech), so one loan reads identically on both. */
export function formatMoney(amount: number, code: string | undefined, locale: string): string {
  if (!code) return Math.round(amount).toLocaleString(locale)
  try {
    return amount.toLocaleString(locale, { style: 'currency', currency: code, maximumFractionDigits: 0 })
  } catch {
    return `${Math.round(amount).toLocaleString(locale)} ${code}`
  }
}

export function currencyCode(c: WireCurrency | null | undefined): string | undefined {
  if (typeof c === 'string') return c
  if (c && typeof c === 'object' && typeof c.code === 'string') return c.code
  return undefined
}
