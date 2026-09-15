// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (relativePath: string) => readFileSync(path.resolve(__dirname, '..', relativePath), 'utf8')

describe('API and process semantic themes', () => {
  it('keeps every API domain category adaptive without concatenating alpha onto CSS variables', () => {
    const api = source('app/docs/api/page.tsx')
    for (const token of ['graph-product', 'graph-account', 'graph-card', 'graph-domain', 'graph-case']) {
      expect(api).toContain(`var(--${token})`)
    }
    expect(api).toContain('color-mix(in srgb, ${groupColor} 8%, transparent)')
    expect(api).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
  })

  it('uses the shared inverse foreground for selected process controls', () => {
    const process = source('components/docs/ProcessView.tsx')
    expect(process.match(/var\(--text-inverse\)/g)).toHaveLength(2)
    expect(process).not.toContain("'#fff'")
  })
})
