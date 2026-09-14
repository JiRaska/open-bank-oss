// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../components/lending/risk/charts.tsx'), 'utf8')

describe('lending risk chart semantics', () => {
  it('keeps decision, IFRS 9 and arrears palettes adaptive and consistently labelled', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const token of ['success', 'warning', 'danger', 'accent', 'info', 'chart-purple']) {
      expect(source).toContain(`var(--${token})`)
    }
    expect(source.match(/role="group"/gu)).toHaveLength(5)
    expect(source).toContain('Weekly engine outcomes: approve, refer and decline')
    expect(source).toContain('Application affordability against policy DSTI and DTI limits')
    expect(source).toContain('Outstanding exposure split by IFRS 9 stage')
  })
})
