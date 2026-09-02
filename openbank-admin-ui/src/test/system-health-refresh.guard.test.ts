// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('System Health refresh contract', () => {
  it('exposes localized busy semantics without changing health checks', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/system/health/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={refreshing}')
    expect(source).toContain('aria-busy={refreshing}')
    expect(source).toContain("aria-label={t('Obnovit zdraví systému', 'Refresh system health')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('refresh(true)')
  })
})
