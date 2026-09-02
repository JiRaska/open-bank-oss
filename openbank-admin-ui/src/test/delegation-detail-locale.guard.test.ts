// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('delegation detail timestamp locale contract', () => {
  it('formats timeline timestamps with the active locale and preserves invalid raw values', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/delegations/[id]/page.tsx'), 'utf8')

    expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('toLocaleString(locale, { dateStyle: \'medium\', timeStyle: \'short\' })')
    expect(source).toContain('if (Number.isNaN(date.getTime())) return value')
    expect(source).toContain('formatDelegationTimestamp(grant.validFrom, dateLocale)')
    expect(source).toContain('formatDelegationTimestamp(grant.closedAt, dateLocale)')
    expect(source).not.toMatch(/grant\.(validFrom|validTo|createdAt|updatedAt|closedAt) \?\? '—'/)
  })
})
