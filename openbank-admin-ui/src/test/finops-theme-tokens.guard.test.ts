// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
const hexLiteral = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])/g

describe('FinOps theme colour contract', () => {
  it('keeps the deliberate categorical domain palette adaptive and labelled', () => {
    const palette = source.match(/const DOMAIN_COLOR: Record<string, string> = \{[\s\S]*?\n\s+\}/)?.[0]

    expect(palette).toBeTruthy()
    expect(source).not.toMatch(hexLiteral)
    for (const domain of ['platform', 'governance', 'security', 'observability', 'finops', 'tax']) {
      expect(palette).toContain(`var(--finops-domain-${domain})`)
    }
    expect(source).toContain('<section aria-labelledby="finops-domain-spend-title"')
    expect(source).toContain('id="finops-domain-spend-title"')
    expect(source).not.toContain('rgba(')
  })
})
