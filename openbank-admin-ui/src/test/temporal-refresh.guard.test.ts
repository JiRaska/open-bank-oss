// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Temporal status refresh contract', () => {
  it('exposes localized busy semantics without changing the status loader', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/temporal/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit stav Temporal', 'Refresh Temporal status')}")
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain("fetch('/api/temporal/status')")
  })
})
