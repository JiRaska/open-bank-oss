// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/approvals/delegation/[id]/page.tsx'), 'utf8')

describe('delegation approval semantic theme', () => {
  it('uses the shared adaptive lifecycle semantics without weakening read-only controls', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const tone of ['warning', 'danger', 'success']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).not.toContain("method: 'POST'")
    expect(source).toContain('This screen is read-only.')
  })
})
