// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('PID quick-create contract', () => {
  it('keeps the entry action explicit and localized', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/pid/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-secondary" onClick={() => setShowNewForm(true)}')
    expect(source).toContain("aria-label={t('Otevřít rychlé vytvoření PID záznamu', 'Open PID quick create')}")
    expect(source).toContain('setShowNewForm(true)')
  })
})
