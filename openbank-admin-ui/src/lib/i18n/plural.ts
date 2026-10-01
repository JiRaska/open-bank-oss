// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Czech plural form for a count: `one` for 1, `few` for 2–4, `many` for everything else
 * (0, 5+, and any non-integer — "1,5 dne" is rare enough that `many` reads acceptably).
 * `czechPlural(2, 'den', 'dny', 'dní')` → `dny`. Use with `t(...)` for the English side.
 */
export function czechPlural(n: number, one: string, few: string, many: string): string {
  if (!Number.isInteger(n)) return many
  const abs = Math.abs(n)
  if (abs === 1) return one
  if (abs >= 2 && abs <= 4) return few
  return many
}

/** English singular/plural for a count. */
export function englishPlural(n: number, one: string, other: string): string {
  return Math.abs(n) === 1 ? one : other
}
