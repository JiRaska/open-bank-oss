// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/docs/cluster/page.tsx'), 'utf8')

describe('cluster dossier semantic theme guard', () => {
  it('uses shared semantics instead of fixed presentation colours', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).not.toMatch(/rgba\(/i)
    expect(source).toContain('var(--success-text)')
    expect(source).toContain('var(--warning-text)')
    expect(source).toContain('var(--sidebar-bg)')
    expect(source).toContain('var(--map-core)')
  })

  it('composes runtime group colours with token-safe colour mixing', () => {
    expect(source).toContain('color-mix(in srgb, ${g.color}')
    expect(source).not.toMatch(/\$\{g\.color}\w+/)
  })
})
