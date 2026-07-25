// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Minor-unit ↔ major-unit conversion for the card limit editor.
//
// The card-issuance API speaks MINOR units only (`dailyLimitMinorUnits`), but an
// operator types — and must read — major units: a limit of 500000 on a CZK card
// is 5 000 Kč, not five hundred thousand. Getting that wrong by a factor of 100
// on a spending limit is exactly the class of back-office mistake that reaches a
// customer, so the conversion lives here, in one tested place, rather than as an
// inline `/100` in a form handler.
//
// Arithmetic is done on the DECIMAL STRING, never via `parseFloat(x) * 100`:
// `19.99 * 100` is 1998.9999999999998 in IEEE-754, which truncates to 1998 — a
// silent one-haléř/one-cent loss on every edit.

/**
 * ISO 4217 currencies with a zero-decimal minor unit: one minor unit IS one
 * major unit. Every other currency in the fleet's catalogue is two-decimal.
 * (Three-decimal currencies — BHD/KWD/TND — are not offered by any OpenBank
 * product; they would need their own entry here before they could be.)
 */
export const ZERO_DECIMAL_CURRENCIES: ReadonlySet<string> = new Set([
  'BIF', 'CLP', 'DJF', 'GNF', 'ISK', 'JPY', 'KMF', 'KRW',
  'PYG', 'RWF', 'UGX', 'VND', 'VUV', 'XAF', 'XOF', 'XPF',
])

/** Digits after the decimal point for `code` (0 or 2). Unknown code ⇒ 2. */
export function currencyExponent(code: string | null | undefined): number {
  if (!code) return 2
  return ZERO_DECIMAL_CURRENCIES.has(code.toUpperCase()) ? 0 : 2
}

/**
 * Minor units → a plain major-unit decimal string ("500000" CZK → "5000.00").
 * No grouping, no symbol — this is the value an `<input>` holds.
 */
export function minorToMajorString(minor: number, code: string | null | undefined): string {
  const exp = currencyExponent(code)
  const negative = minor < 0
  const digits = Math.abs(Math.trunc(minor)).toString().padStart(exp + 1, '0')
  const whole = digits.slice(0, digits.length - exp)
  const frac = exp === 0 ? '' : `.${digits.slice(digits.length - exp)}`
  return `${negative ? '-' : ''}${whole}${frac}`
}

/**
 * A major-unit string an operator typed → minor units, or `null` when the text
 * is not a value this currency can hold.
 *
 * Accepts a comma or a dot as the decimal separator (a Czech operator types
 * "5 000,50") and ignores spaces/NBSP used as thousands separators. Rejects:
 * empty text, anything non-numeric, a negative sign (the domain forbids negative
 * limits — see `rules.ts`), and more decimals than the currency has minor units
 * (silently rounding a typed "10.999" would change the operator's intent).
 */
export function parseMajorToMinor(text: string, code: string | null | undefined): number | null {
  const cleaned = text.replace(/[\s  ]/g, '').replace(',', '.')
  if (cleaned.length === 0) return null
  if (!/^\d+(\.\d*)?$/.test(cleaned)) return null
  const exp = currencyExponent(code)
  const [whole, frac = ''] = cleaned.split('.')
  if (frac.length > exp) return null
  const minor = Number(`${whole}${frac.padEnd(exp, '0')}`)
  return Number.isSafeInteger(minor) ? minor : null
}

/**
 * Minor units → a localized display string with the currency ("5 000,00 Kč").
 * Falls back to `<amount> <CODE>` when `code` is not a currency `Intl` knows, so
 * an unexpected code degrades to something readable instead of throwing.
 */
export function formatMinor(minor: number, code: string | null | undefined, locale = 'en-US'): string {
  const exp = currencyExponent(code)
  const major = Number(minorToMajorString(minor, code))
  if (code) {
    try {
      return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency: code.toUpperCase(),
        minimumFractionDigits: exp,
        maximumFractionDigits: exp,
      }).format(major)
    } catch {
      // Not a currency code Intl accepts — fall through to the plain form.
    }
  }
  const amount = new Intl.NumberFormat(locale, {
    minimumFractionDigits: exp,
    maximumFractionDigits: exp,
  }).format(major)
  return code ? `${amount} ${code.toUpperCase()}` : amount
}

/**
 * A MAJOR-unit amount (e.g. `monthlyFeePerCard` from the entitlements API, a
 * plain `Double` — not minor units like a card limit) as a localized currency
 * string. Same fallback behaviour as `formatMinor` for an unknown code.
 */
export function formatMajor(major: number, code: string | null | undefined, locale = 'en-US'): string {
  const exp = currencyExponent(code)
  if (code) {
    try {
      return new Intl.NumberFormat(locale, {
        style: 'currency',
        currency: code.toUpperCase(),
        minimumFractionDigits: exp,
        maximumFractionDigits: exp,
      }).format(major)
    } catch {
      // Not a currency code Intl accepts — fall through to the plain form.
    }
  }
  const amount = new Intl.NumberFormat(locale, {
    minimumFractionDigits: exp,
    maximumFractionDigits: exp,
  }).format(major)
  return code ? `${amount} ${code.toUpperCase()}` : amount
}
