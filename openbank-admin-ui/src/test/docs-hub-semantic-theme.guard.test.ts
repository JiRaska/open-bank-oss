// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/docs/page.tsx'), 'utf8')

describe('documentation hub semantic theme guard', () => {
  it('uses shared theme semantics instead of fixed presentation colours', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).toContain('var(--accent-text)')
    expect(source).toContain('var(--info-text)')
    expect(source).toContain('var(--success-text)')
    expect(source).toContain('var(--danger-text)')
    expect(source).toContain('color-mix(in srgb,')
  })

  it('does not present a stale fixed service count as live catalog truth', () => {
    expect(source).not.toContain('33 services')
    expect(source).toContain("badge: 'Live catalog'")
  })
})
