// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
const hexLiteral = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])/g

describe('FinOps theme colour contract', () => {
  it('keeps raw colours only in the deliberate categorical domain palette', () => {
    const palette = source.match(/const DOMAIN_COLOR: Record<string, string> = \{[\s\S]*?\n\s+\}/)?.[0]

    expect(palette).toBeTruthy()
    expect(palette?.match(hexLiteral)).toEqual([
      '#6366f1', '#d97706', '#dc2626', '#0891b2', '#16a34a', '#94a3b8',
    ])
    expect(source.replace(palette!, '')).not.toMatch(hexLiteral)
    expect(source).not.toContain('rgba(')
  })
})
