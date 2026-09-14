// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/services/page.tsx'), 'utf8')

describe('service catalogue semantic theme contract', () => {
  it('shares domain colours with the platform map', () => {
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    for (const token of [
      '--map-core', '--map-identity', '--map-payment',
      '--map-compliance', '--map-psd2', '--map-platform',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps live discovery and documentation routing authoritative', () => {
    expect(source).toContain("fetch('/api/services/health'")
    expect(source).toContain('/api/services/${c.id}/docs')
    expect(source).toContain('catalogShortFor')
    expect(source).toContain('<CatalogDriftBanner')
  })
})
