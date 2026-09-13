// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const tabs = readFileSync(join(process.cwd(), 'src/components/ui/Tabs.tsx'), 'utf8')

describe('shared tabs contrast contract', () => {
  it('never renders selected text with the fill accent token', () => {
    expect(tabs).toContain("color: selected ? 'var(--accent-text)' : 'var(--text-secondary)'")
    expect(tabs).not.toContain("color: selected ? 'var(--accent)'")
  })
})
