// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache-2.0 license.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('Cards refresh contract', () => {
  it('exposes localized busy semantics without changing card loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/cards/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('onClick={reload}')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit karty', 'Refresh cards')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
  })
})
