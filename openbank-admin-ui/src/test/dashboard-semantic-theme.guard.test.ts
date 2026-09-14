// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(path.resolve(__dirname, '../app/dashboard/Dashboard.module.css'), 'utf8')

describe('dashboard semantic theme', () => {
  it('keeps the stable night-sky identity named while content surfaces stay adaptive', () => {
    expect(css).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(css).not.toMatch(/rgba?\(/u)
    expect(css).toContain('var(--dashboard-hero-start)')
    expect(css).toContain('var(--dashboard-health-start)')
    expect(css).toContain('linear-gradient(120deg, var(--surface), var(--surface-2))')
    expect(css).toContain('var(--success-bg)')
    expect(css).toContain('var(--danger-bg)')
  })
})
