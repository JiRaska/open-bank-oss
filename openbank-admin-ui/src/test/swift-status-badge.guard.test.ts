// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('SWIFT status presentation contract', () => {
  it('uses the shared badge and the service lifecycle contract', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/swift/page.tsx'), 'utf8')

    expect(source).toContain("import { StatusBadge } from '@/components/ui'")
    expect(source).toContain('<StatusBadge status={m.status} tone={swiftStatusTone(m.status)} />')
    expect(source).toContain('swiftLifecycleGroup(m.status)')
    expect(source).not.toContain('PROCESSING')
    expect(source).not.toContain('STATUS_COLORS')
  })
})
