// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('infrastructure status presentation', () => {
  it('uses the shared semantic badge and retains its distinct health icons', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/infrastructure/page.tsx'), 'utf8')

    expect(source).toContain("from '@/components/ui'")
    expect(source).toContain('StatusBadge')
    expect(source).toContain('function InfrastructureStatusBadge')
    expect(source).toContain('<StatusBadge status={status} leading={icon} />')
    expect(source).toContain("cn('card tone-border-left', TONE_BORDER_LEFT_CLASS[tone])")
    expect(source).toContain('<CheckCircle2 size={13} />')
    expect(source).toContain('<XCircle size={13} />')
    expect(source).not.toContain('STATUS_STYLES')
  })
})
