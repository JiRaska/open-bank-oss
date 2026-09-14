// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/brand/ExplorerGuide.module.css'), 'utf8')

describe('Explorer guide semantic theme contract', () => {
  it('owns its educational brand palette through shared tokens', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--explorer-guide-start', '--explorer-guide-mid', '--explorer-guide-end',
      '--explorer-guide-accent', '--explorer-guide-on-brand', '--explorer-guide-body',
      '--explorer-guide-border', '--explorer-guide-portrait', '--explorer-guide-portrait-shadow',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('preserves compact and mobile education layouts', () => {
    expect(source).toContain('.compact .copy')
    expect(source).toContain('.compact .portrait')
    expect(source).toContain('@media (max-width: 720px)')
  })
})
