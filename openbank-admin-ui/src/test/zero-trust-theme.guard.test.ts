// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(__dirname, '..')
const page = readFileSync(path.join(root, 'app/docs/zero-trust/page.tsx'), 'utf8')
const globals = readFileSync(path.join(root, 'app/globals.css'), 'utf8')

describe('Zero-Trust educational map theme', () => {
  it('pairs every labelled perimeter with adaptive foreground and background tokens', () => {
    expect(page).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const layer of ['network', 'transport', 'identity', 'authorization']) {
      expect(page).toContain(`color: 'var(--zero-trust-${layer})'`)
      expect(page).toContain(`background: 'var(--zero-trust-${layer}-bg)'`)
      expect(globals.match(new RegExp(`--zero-trust-${layer}:`, 'gu'))).toHaveLength(2)
      expect(globals.match(new RegExp(`--zero-trust-${layer}-bg:`, 'gu'))).toHaveLength(2)
    }
    expect(page).toContain('background: p.background')
    expect(page).not.toMatch(/\$\{p\.color\}(?:08|[0-9a-f]{2})/iu)
    expect(page).toContain("fontWeight: 600, color: 'var(--danger-text)'")
  })
})
