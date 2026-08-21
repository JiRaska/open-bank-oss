// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('FinOps cost allocation refresh contract', () => {
  it('exposes localized busy semantics without changing the allocation loader', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/finops/allocation/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit rozpad nákladů', 'Refresh cost allocation')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain("fetch('/api/finops/allocation'")
  })
})
