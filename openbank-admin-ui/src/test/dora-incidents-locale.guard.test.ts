// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('DORA incident locale contract', () => {
  it('formats evidence timestamps with the active operator locale', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/security/incidents/page.tsx'), 'utf8')

    expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(dateLocale)')
    expect(source).not.toContain('toLocaleString()')
    expect(source).toContain('AuthGuard permission="system:view"')
  })
})
