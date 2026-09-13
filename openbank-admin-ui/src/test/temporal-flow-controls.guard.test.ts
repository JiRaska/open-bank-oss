// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Temporal Flow controls contract', () => {
  it('exposes localized control semantics without changing the status loader', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/temporal/flow/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('aria-pressed={flow}')
    expect(source).toContain("aria-label={t(flow ? 'Pozastavit tok workflow' : 'Spustit tok workflow'")
    expect(source).toContain('aria-busy={isChecking}')
    expect(source).toContain("aria-label={t('Obnovit stav Temporal', 'Refresh Temporal status')}")
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain("fetch('/api/temporal/status'")
  })
})
