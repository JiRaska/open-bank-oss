// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('screen feedback refresh contract', () => {
  it('prevents overlapping loads and exposes localized busy semantics', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/feedback/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={cs ? 'Obnovit zpětnou vazbu k obrazovkám' : 'Refresh screen feedback'}")
    expect(source).toContain("fetch('/api/feedback/screen-feedback'")
  })
})
