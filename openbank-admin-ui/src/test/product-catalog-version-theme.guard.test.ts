// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')

describe('product catalogue version history theme contract', () => {
  it('uses valid theme-aware colours for the current row and badge', () => {
    expect(source).toContain("color-mix(in srgb, var(--accent) 5%, var(--surface))")
    expect(source).toContain("color-mix(in srgb, var(--accent) 19%, var(--border))")
    expect(source).toContain("background: 'var(--accent-bg)', color: 'var(--accent-text)', border: '1px solid var(--accent-border)'")
  })

  it('does not concatenate alpha suffixes onto CSS custom properties', () => {
    expect(source).not.toMatch(/var\(--[\w-]+\)[\da-f]{2}\b/i)
  })

  it('keeps compact version badges readable without sub-10px copy', () => {
    expect(source).toContain("!v.isPublic && <span style={{ fontSize: '10px'")
    expect(source).toContain("isCurrent && <span style={{ fontSize: '10px'")
  })
})
