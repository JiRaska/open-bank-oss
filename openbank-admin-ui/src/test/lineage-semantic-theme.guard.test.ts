// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/docs/lineage/page.tsx'), 'utf8')

describe('data lineage semantic theme guard', () => {
  it('keeps domain, relationship, and interface colours on shared tokens', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).toContain('var(--map-core)')
    expect(source).toContain('var(--map-payment)')
    expect(source).toContain('var(--map-compliance)')
    expect(source).toContain('var(--map-identity)')
    expect(source).toContain('var(--info-bg)')
    expect(source).toContain('var(--success-bg)')
  })

  it('uses token-safe colour mixing instead of appending alpha hex suffixes', () => {
    expect(source).toContain('color-mix(in srgb,')
    expect(source).not.toMatch(/\.color}\d[a-f0-9]/i)
  })
})
