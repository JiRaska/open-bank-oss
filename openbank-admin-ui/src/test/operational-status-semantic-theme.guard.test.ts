// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = (relativePath: string) => readFileSync(path.resolve(__dirname, '..', relativePath), 'utf8')

describe('operational status semantic themes', () => {
  it.each(['app/docs/adr/page.tsx', 'app/docs/adr/[slug]/page.tsx'])('%s pairs status copy with adaptive text tokens', relativePath => {
    const adr = source(relativePath)
    for (const tone of ['success', 'info', 'warning', 'danger']) {
      expect(adr).toContain(`var(--${tone}-text)`)
      expect(adr).toContain(`var(--${tone}-bg)`)
    }
    expect(adr).not.toMatch(/var\(--[a-z]+,\s*#[0-9a-f]{3,6}\)/i)
  })

  it('keeps a failed party lookup readable through the shared warning pair', () => {
    const lookup = source('components/kyc/PartyLookup.tsx')
    for (const token of ['warning-text', 'warning-bg', 'warning-border']) {
      expect(lookup).toContain(`var(--${token})`)
    }
  })

  it('uses strong semantic fills for inverse origination glyphs', () => {
    const flow = source('components/lending/OriginationFlow.tsx')
    for (const token of ['success-text', 'selection-bg', 'danger-text', 'text-inverse']) {
      expect(flow).toContain(`var(--${token})`)
    }
    expect(flow).not.toContain("color: '#fff'")
  })

  it('keeps SBOM status and chart styling token-owned without raw fallbacks', () => {
    const sbom = source('components/sbom/SbomViewer.tsx')
    expect(sbom).toContain("color: 'var(--warning-text)'")
    expect(sbom).not.toMatch(/var\(--(?:warning|accent),\s*#[0-9a-f]{3,6}\)/i)
  })
})
