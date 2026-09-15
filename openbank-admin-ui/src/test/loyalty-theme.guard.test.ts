// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(__dirname, '..')
const moduleCss = readFileSync(path.join(root, 'app/loyalty/loyalty.module.css'), 'utf8')
const globals = readFileSync(path.join(root, 'app/globals.css'), 'utf8')

describe('Lípa workspace theme identity', () => {
  it('keeps programme colours in named tokens and component surfaces adaptive', () => {
    expect(moduleCss).not.toMatch(/#[0-9a-f]{3,8}\b/iu)
    for (const token of [
      'hero-start', 'hero-end', 'action', 'on-brand', 'brand-accent',
      'brand-muted', 'brand-soft', 'brand-border', 'brand-wash', 'card-shadow',
    ]) {
      expect(moduleCss).toContain(`var(--loyalty-${token})`)
      expect(globals).toContain(`--loyalty-${token}:`)
    }
    expect(moduleCss).toContain('background: var(--surface)')
    expect(moduleCss).toContain('color: var(--text-primary)')
  })
})
