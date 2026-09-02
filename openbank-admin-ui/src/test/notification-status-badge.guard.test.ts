// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('notification status presentation contract', () => {
  it('uses the shared semantic badge rather than a page-local colour map', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/notifications/page.tsx'), 'utf8')

    expect(source).toContain("import { PageHeader, StatusBadge } from '@/components/ui'")
    expect(source).toContain('<StatusBadge status={n.status} />')
    expect(source).not.toContain('STATUS_COLOR')
  })
})
