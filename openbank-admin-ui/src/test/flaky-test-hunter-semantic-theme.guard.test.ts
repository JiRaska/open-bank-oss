// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const detail = readFileSync(path.resolve(__dirname, '../app/iaops/flaky-test-hunter/[id]/page.tsx'), 'utf8')

describe('Flaky Test Hunter semantic theme', () => {
  it('keeps actionable evidence adaptive and visually identifiable', () => {
    expect(detail).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    expect(detail).toContain("color: 'var(--accent-text)'")
    expect(detail).toContain("textDecoration: 'underline'")
  })
})
