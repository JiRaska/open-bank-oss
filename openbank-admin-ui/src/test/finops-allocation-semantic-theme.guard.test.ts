// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/finops/allocation/page.tsx'), 'utf8')

describe('FinOps allocation semantic theme guard', () => {
  it('uses shared semantic tokens and token-safe colour mixing', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).not.toMatch(/rgba?\(\s*\d/i)
    expect(source).not.toContain('${color}18')
    expect(source).toContain('color-mix(in srgb, ${color} 12%, var(--surface))')
    expect(source).toContain("compliance: 'var(--danger)'")
    expect(source).toContain("payments: 'var(--success)'")
    expect(source).toContain("'open-banking': 'var(--info)'")
  })
})
