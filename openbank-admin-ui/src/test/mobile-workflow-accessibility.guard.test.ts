// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relative: string) => readFileSync(path.resolve(__dirname, '..', relative), 'utf8')

describe('mobile workflow accessibility regressions', () => {
  it('does not communicate inactive dashboard or FX state by fading readable data', () => {
    const dashboard = read('app/dashboard/Dashboard.module.css')
    const fx = read('app/fx/page.tsx')

    const plannedRule = dashboard.match(/\.serviceChipPlanned\s*{([^}]*)}/)?.[1] ?? ''
    expect(plannedRule).toContain('color: var(--text-secondary)')
    expect(plannedRule).not.toContain('opacity:')
    expect(fx).not.toContain('opacity: r.published')
    expect(fx).toContain("background: r.published ? undefined : 'var(--surface-2)'")
  })

  it('keeps mobile evidence actions and campaign branding on AA-safe colours', () => {
    expect(read('app/system/tests/page.tsx')).toContain(
      "color: 'var(--accent-text)', fontSize: 11, fontWeight: 650",
    )
    expect(read('app/globals.css')).toContain(
      '.campaign-phone-topline { display: flex; justify-content: space-between; color: #4f46e5;',
    )
  })

  it('keeps the mobile payments table keyboard-scrollable with a real header label', () => {
    const payments = read('app/payments/page.tsx')
    expect(payments).toContain(
      'className="table-scroll-region" tabIndex={0} aria-label={t(\'Posuvný seznam plateb\'',
    )
    expect(payments).toContain('<caption className="sr-only">')
    expect(payments).toContain("<th style={{ width: '36px' }}><span className=\"sr-only\">{t('Detail', 'Detail')}</span></th>")
    expect(read('app/globals.css')).toContain('contain: layout paint inline-size;')
  })

  it('treats a failed test-intelligence response as unavailable instead of report data', () => {
    expect(read('app/system/tests/page.tsx')).toContain(
      'if (!response.ok) throw new Error(`Test intelligence request failed with HTTP ${response.status}`)',
    )
  })
})
