// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Money for a human reader. `formatMoneyExact` keeps the cents (a posting amount must reconcile to
 * the haléř); `formatMoneyCompact` is for magnitudes a reader compares rather than reconciles —
 * limits, exposures, headroom — and reads `100 mld. Kč` / `20 mil. Kč` in Czech, `CZK 100B` in English.
 * An unknown currency code falls back to `<number> <code>` rather than throwing.
 */
export function formatMoneyExact(amount: number, currency: string, locale: string): string {
  try {
    return amount.toLocaleString(locale, { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 })
  } catch {
    return `${amount.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`
  }
}

export function formatMoneyCompact(amount: number, currency: string, locale: string): string {
  try {
    return amount.toLocaleString(locale, { style: 'currency', currency, notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 2 })
  } catch {
    return `${amount.toLocaleString(locale, { notation: 'compact', minimumFractionDigits: 0, maximumFractionDigits: 2 })} ${currency}`
  }
}
