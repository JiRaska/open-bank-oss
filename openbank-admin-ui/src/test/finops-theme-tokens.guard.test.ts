// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/finops/page.tsx'), 'utf8')
const hexLiteral = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])/g

describe('FinOps theme colour contract', () => {
  it('uses semantic tokens for infrastructure cost categories', () => {
    expect(source).toContain('const AWS_CATEGORY_COLOR: Record<string, string>')
    expect(source).not.toMatch(hexLiteral)
    expect(source).not.toContain('rgba(')
    expect(source).toContain("Unclassified: 'var(--text-tertiary)'")
  })
})
