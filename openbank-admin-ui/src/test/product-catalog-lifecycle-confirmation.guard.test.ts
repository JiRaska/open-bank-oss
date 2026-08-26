// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')

describe('Product Catalog lifecycle confirmation', () => {
  it('replaces the native prompt with an accessible in-product decision', () => {
    expect(source).not.toContain('window.confirm')
    expect(source).toContain('role="dialog"')
    expect(source).toContain('aria-modal="true"')
    expect(source).toContain('aria-describedby="catalog-lifecycle-impact"')
    expect(source).toContain("event.key === 'Escape' && !lifecycleBusy")
    expect(source).toContain("event.key === 'Tab'")
    expect(source).toContain('lifecycleCancelRef')
    expect(source).toContain('lifecycleConfirmRef')
    expect(source).toContain('autoFocus')
  })

  it('explains both state transitions and prevents duplicate requests', () => {
    expect(source).toContain("pendingLifecycle.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'")
    expect(source).toContain('History and existing accounts remain available.')
    expect(source).toContain('The product becomes available for new accounts.')
    expect(source).toContain('if (!p?.id || lifecycleBusy) return')
    expect(source).toContain('aria-busy={lifecycleBusy}')
  })

  it('preserves optimistic locking and the existing lifecycle endpoints', () => {
    expect(source).toContain("const action = p.status === 'ACTIVE' ? 'deactivate' : 'activate'")
    expect(source).toContain("method: 'POST'")
    expect(source).toContain("headers: { 'If-Match': `\"${p.revision}\"` }")
    expect(source).toContain("<Can permission=\"catalog:author\">")
  })
})
