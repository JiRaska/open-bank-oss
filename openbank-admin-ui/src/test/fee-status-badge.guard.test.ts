// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('fee status presentation contract', () => {
  it('uses the shared semantic badge rather than a page-local colour map', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/fees/page.tsx'), 'utf8')

    expect(source).toContain("import { PageHeader, StatCard, StatusBadge } from '@/components/ui'")
    expect(source).toContain('<StatusBadge status={fee.status} />')
    expect(source).not.toContain('STATUS_COLOR')
  })
})
