// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/sanctions/page.tsx'), 'utf8')

describe('sanctions semantic theme guard', () => {
  it('uses shared semantic tokens instead of fixed presentation colours', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).not.toMatch(/rgba?\(\s*\d/i)
    expect(source).not.toMatch(/color:\s*["']white["']/i)
    expect(source).toContain('var(--danger-bg)')
    expect(source).toContain('var(--accent-bg)')
    expect(source).toContain('var(--info-bg)')
    expect(source).toContain('var(--text-inverse)')
  })
})
