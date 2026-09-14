// SPDX-License-Identifier: Apache-2.0

import { render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CatalogDriftBanner } from '@/components/governance/CatalogDriftBanner'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(() => vi.unstubAllGlobals())

describe('catalog drift banner', () => {
  it('names only missing services of an allowed kind', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ services: [
      { short: 'account', kind: 'service' },
      { short: 'ledger', kind: 'service' },
      { short: 'admin-ui', kind: 'frontend' },
    ] }), { status: 200, headers: { 'content-type': 'application/json' } })))

    render(
      <LanguageProvider initialLanguage="en">
        <CatalogDriftBanner present={['account']} />
      </LanguageProvider>,
    )

    const banner = await screen.findByRole('status')
    expect(banner).toHaveTextContent('1 service(s) this page does not show')
    expect(banner).toHaveTextContent('ledger')
    expect(banner).not.toHaveTextContent('admin-ui')
  })

  it('uses the shared warning theme without local colours', () => {
    const source = readFileSync(path.resolve(__dirname, '../components/governance/CatalogDriftBanner.tsx'), 'utf8')
    expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
    expect(source).toContain("background: 'var(--warning-bg)'")
    expect(source).toContain("border: '1px solid var(--warning-border)'")
    expect(source).toContain("color: 'var(--warning-text)'")
  })
})
