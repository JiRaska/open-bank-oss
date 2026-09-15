// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const layout = readFileSync(path.resolve(__dirname, '../app/layout.tsx'), 'utf8')

describe('theme server bootstrap', () => {
  it('applies the persisted theme before the browser paints the app shell', () => {
    expect(layout).toContain('requestCookies.get(THEME_COOKIE_KEY)?.value')
    expect(layout).toContain("className={initialTheme === 'dark' ? 'dark' : undefined}")
    expect(layout).toContain('initialTheme={initialTheme}')
    expect(layout).toContain('suppressHydrationWarning')
  })
})
