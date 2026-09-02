// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = readFileSync(path.resolve(__dirname, '../app/devops/page.tsx'), 'utf8')

describe('DevOps test-intelligence consolidation', () => {
  it('does not reintroduce the stale JUnit-only test-results projection', () => {
    expect(page).not.toContain("fetch('/api/test-results'")
    expect(page).not.toContain("@/lib/types/test-results")
    expect(page).toContain('href="/system/tests"')
    expect(page).toContain('The authoritative view for CI runs')
  })
})
