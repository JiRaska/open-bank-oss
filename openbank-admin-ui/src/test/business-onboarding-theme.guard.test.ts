// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/business-onboarding/page.tsx'), 'utf8')

describe('business onboarding decision theme semantics', () => {
  it('pairs every human-attestation state with adaptive text, background and border tokens', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const tone of ['success', 'warning', 'danger']) {
      expect(source).toContain(`color: 'var(--${tone}-text)'`)
      expect(source).toContain(`background: 'var(--${tone}-bg)'`)
      expect(source).toContain(`border: 'var(--${tone}-border)'`)
    }
    expect(source).not.toContain('STATE_COLOR')
    expect(source).not.toMatch(/\$\{stateTone\.color\}(?:15|30)/u)
  })

  it('schedules both initial loaders without mount-time state cascades and preserves cleanup', () => {
    expect(source.match(/const initialId = window\.setTimeout\(load, 0\)/gu)).toHaveLength(2)
    expect(source.match(/window\.clearTimeout\(initialId\)/gu)).toHaveLength(2)
    expect(source).not.toMatch(/useEffect\(\(\) => \{\s*load\(\)/u)
    expect(source).toContain('generation.current += 1')
    expect(source).toContain('loadGeneration.current += 1')
  })
})
