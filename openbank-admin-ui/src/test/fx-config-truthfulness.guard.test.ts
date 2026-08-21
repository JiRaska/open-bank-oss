// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('FX configuration truthfulness', () => {
  it('does not present browser-only bank-sheet state as persisted configuration', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/fx/page.tsx'), 'utf8')

    expect(source).toContain('const FX_CONFIGURATION_WRITABLE = false')
    expect(source).toContain('persistence is not configured.')
    expect(source).toContain('disabled={!FX_CONFIGURATION_WRITABLE}')
    expect(source).not.toContain('fx-service persistence is planned.')
  })
})
