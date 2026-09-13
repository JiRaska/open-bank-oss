// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('production readiness refresh contract', () => {
  it('exposes localized busy semantics and preserves the readiness endpoint', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/system/readiness/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit připravenost na produkci', 'Refresh production readiness')}")
    expect(source).toContain('<RefreshCw size={15} aria-hidden="true" />')
    expect(source).toContain("fetch('/api/prod-readiness'")
  })
})
