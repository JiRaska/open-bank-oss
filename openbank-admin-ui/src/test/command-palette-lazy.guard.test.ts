// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const header = readFileSync(resolve(process.cwd(), 'src/components/layout/Header.tsx'), 'utf8')

describe('command palette shell cost', () => {
  it('loads the search dialog on operator intent instead of every shell render', () => {
    expect(header).toContain("dynamic(")
    expect(header).toContain("import('@/components/search/CommandPalette')")
    expect(header).not.toContain("import { CommandPalette } from '@/components/search/CommandPalette'")
    expect(header).toContain('onMouseEnter={warmCommandPalette}')
    expect(header).toContain('onFocus={warmCommandPalette}')
    expect(header).toContain('paletteActivated && <CommandPalette')
  })
})
