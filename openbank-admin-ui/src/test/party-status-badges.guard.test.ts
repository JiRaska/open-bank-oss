// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('party status presentation contract', () => {
  it('uses shared badges for both party and KYC states', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/parties/page.tsx'), 'utf8')

    expect(source).toContain("import { PageHeader, StatusBadge } from '@/components/ui'")
    expect(source).toContain('<StatusBadge status={p.status} />')
    expect(source).toContain("<StatusBadge status={p.kycStatus} label={p.kycStatus?.replace('_', ' ')} />")
    expect(source).not.toContain('KYC_COLORS')
    expect(source).not.toContain('STATUS_COLORS')
  })
})
