// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export type Language = 'en' | 'cs'

export const LANG_COOKIE = 'openbank-admin-lang'
export const LANG_STORAGE_KEY = 'openbank-admin-lang'

export function parseLanguage(value: string | null | undefined): Language | null {
  return value === 'en' || value === 'cs' ? value : null
}
