// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('SWIFT raw payload disclosure contract', () => {
  it('keeps raw payload disclosure related and explicit', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/swift/[id]/page.tsx'), 'utf8')
    expect(source).toContain('type="button" aria-expanded={showRaw} aria-controls="swift-raw-payload"')
    expect(source).toContain('id="swift-raw-payload"')
    expect(source).toContain("aria-label={showRaw ? t('Skrýt surový payload', 'Hide raw payload') : t('Zobrazit surový payload', 'Show raw payload')}")
    expect(source).toContain('<ChevronDown size={14} aria-hidden="true" />')
  })
})
