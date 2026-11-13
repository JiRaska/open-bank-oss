// Copyright 2026 OpenBank OSS contributors
// SPDX-License-Identifier: Apache-2.0
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Bilingual shape for user-visible DATA (catalog entries, generated JSON, API payloads).
 * UI copy written in JSX uses `t(cs, en)`; data that travels as values uses `{ cs, en }`
 * and is resolved at render time. The `czech-ui-strings` guard (src/test) accepts Czech
 * text only inside one of these two shapes.
 */
export interface Bilingual {
  cs: string
  en: string
}

export type Lang = 'cs' | 'en'

export function bi(cs: string, en: string): Bilingual {
  return { cs, en }
}

export function isBilingual(value: unknown): value is Bilingual {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === 2 && typeof (value as Bilingual).cs === 'string' && typeof (value as Bilingual).en === 'string'
}

/** Pick one language from a Bilingual; plain strings pass through untouched. */
export function pick(value: Bilingual | string, lang: Lang): string {
  return typeof value === 'string' ? value : value[lang]
}

/** Type of `T` after every nested Bilingual has been resolved to a string. */
export type Localized<T> = T extends Bilingual
  ? string
  : T extends (infer U)[]
    ? Localized<U>[]
    : T extends object
      ? { [K in keyof T]: Localized<T[K]> }
      : T

/** Deep-resolve every `{ cs, en }` leaf in a payload to the given language. */
export function localize<T>(value: T, lang: Lang): Localized<T> {
  if (isBilingual(value)) return value[lang] as Localized<T>
  if (Array.isArray(value)) return value.map((v) => localize(v, lang)) as Localized<T>
  if (value !== null && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value)) out[k] = localize(v, lang)
    return out as Localized<T>
  }
  return value as Localized<T>
}
