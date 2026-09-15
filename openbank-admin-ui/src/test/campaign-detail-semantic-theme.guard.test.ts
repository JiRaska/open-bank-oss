// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/campaigns/[id]/page.tsx'), 'utf8')

describe('campaign detail semantic theme', () => {
  it('keeps decision evidence adaptive without weakening lifecycle controls', () => {
    expect(source).not.toMatch(/\b(?:bg|text|border|hover:bg|hover:text|hover:border)-(?:white|slate|violet|emerald|rose|amber|red|indigo)-/u)
    for (const token of ['surface', 'surface-2', 'border', 'text-primary', 'text-secondary', 'text-tertiary', 'accent-bg', 'accent-border', 'accent-text', 'danger-bg', 'danger-border', 'danger-text']) {
      expect(source).toContain(`var(--${token})`)
    }
    expect(source).toContain('role="alertdialog"')
    expect(source).toContain('aria-busy={busy}')
    expect(source).toContain("action === 'activate' ? 'campaign:activate'")
    expect(source).not.toMatch(/<dl className="mt-5 grid gap-3[^>]*>[\s\S]{0,500}<div className="grid gap-3 sm:grid-cols-3">/u)
    expect(source).toContain('<dl className="mt-5 grid gap-3')
  })
})
