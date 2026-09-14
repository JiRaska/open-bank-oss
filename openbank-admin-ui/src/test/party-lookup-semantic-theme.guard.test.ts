// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/kyc/PartyLookup.tsx'), 'utf8')

describe('party lookup semantic theme contract', () => {
  it('uses shared warning semantics for an inconclusive lookup', () => {
    // The component contains an issue reference (#5904), not a CSS colour.
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    expect(source).toContain("data-state=\"failed\"")
    expect(source).toContain("color: 'var(--warning-text)'")
    expect(source).toContain("background: 'var(--warning-bg)'")
    expect(source).toContain("border: '1px solid var(--warning-border)'")
  })

  it('keeps empty and failed outcomes structurally distinct', () => {
    expect(source).toContain("data-state=\"none\"")
    expect(source).toContain("data-state=\"failed\"")
    expect(source).toContain('This is NOT evidence that no such customer exists.')
  })
})
