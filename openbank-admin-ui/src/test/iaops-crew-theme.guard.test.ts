// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const root = path.resolve(__dirname, '..')
const page = readFileSync(path.join(root, 'app/iaops/page.tsx'), 'utf8')
const globals = readFileSync(path.join(root, 'app/globals.css'), 'utf8')

describe('IAOps crew hero semantics', () => {
  it('is a named educational region backed by a deliberate token palette', () => {
    expect(page).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(page).toContain('id="iaops-crew" aria-labelledby="iaops-crew-title"')
    for (const token of ['start', 'mid', 'end', 'heading', 'accent', 'text', 'chip-text', 'border', 'shadow', 'chip-bg', 'chip-border', 'art-wash', 'caption']) {
      expect(page).toContain(`var(--iaops-crew-${token})`)
      expect(globals).toContain(`--iaops-crew-${token}:`)
    }
  })
})
