// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('account detail locale contract', () => {
  it('uses the active UI locale for dates and monetary values', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/accounts/[id]/page.tsx'), 'utf8')

    expect(source).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(numberLocale)')
    expect(source).toContain('toLocaleString(numberLocale, { minimumFractionDigits: 2 })')
    expect(source).not.toContain("toLocaleString('en-US'")
    expect(source).not.toContain("toLocaleString('en-GB'")
  })
})
