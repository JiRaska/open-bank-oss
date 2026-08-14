// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = readFileSync(path.resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
const proxy = readFileSync(path.resolve(__dirname, '../app/api/svc/[service]/[...path]/route.ts'), 'utf8')

describe('product catalog optimistic-concurrency path', () => {
  it('fails safe while an old catalog response has no revision', () => {
    expect(page).toContain('revision?: number')
    expect(page.match(/revision === undefined/g)).toHaveLength(2)
    expect(page).toContain('Catalog is upgrading; reload the page.')
  })

  it('does not offer generic editing for an active product', () => {
    expect(page).toContain("if (p.status === 'ACTIVE')")
    expect(page).toContain("disabled={p.status === 'ACTIVE'}")
    expect(page).toContain('Deactivate the active product before editing.')
    expect(page).toContain('<select className="input" disabled value={formData.status')
  })

  it('carries the strong precondition through the BFF and exposes the next ETag', () => {
    expect(page).toContain("'If-Match': `\"${editingProduct.revision}\"`")
    expect(page).toContain("'If-Match': `\"${p.revision}\"`")
    expect(proxy).toMatch(/FORWARD_HEADERS[\s\S]*'if-match'/)
    expect(proxy).toContain("upstream.headers.get('etag')")
    expect(proxy).toContain("responseHeaders.set('etag', etag)")
  })
})
