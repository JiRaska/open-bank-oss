// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('DevOps status presentation', () => {
  it('uses theme-aware semantic colours instead of page-local hex values', () => {
    const source = readFileSync(path.join(process.cwd(), 'src/app/devops/page.tsx'), 'utf8')
    expect(source).not.toMatch(/#[\da-f]{6}\b/i)
  })
})
