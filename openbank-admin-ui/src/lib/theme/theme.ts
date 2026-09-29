// SPDX-License-Identifier: Apache-2.0

export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'ob-admin-theme'
export const THEME_COOKIE_KEY = 'ob-admin-theme'

export function parseTheme(value: string | null | undefined): Theme | null {
  return value === 'dark' || value === 'light' ? value : null
}
