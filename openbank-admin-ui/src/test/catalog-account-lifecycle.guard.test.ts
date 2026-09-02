// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')

describe('catalog and account lifecycle controls', () => {
  it('offers active term-deposit products when opening an account', () => {
    const page = source('../app/accounts/new/page.tsx')

    expect(page).toContain("'TERM_DEPOSIT'")
    expect(page).toContain("/api/v1/products?status=ACTIVE")
    expect(page).toContain('selectProduct(event.target.value)')
  })

  it('gates catalog mutations and account closure with their write permissions', () => {
    const catalog = source('../app/product-catalog/page.tsx')
    const account = source('../app/accounts/[id]/page.tsx')

    expect(catalog).toContain('<Can permission="catalog:author">')
    expect(account).toContain('<Can permission="accounts:close">')
    expect(account).toContain('<Can permission="accounts:freeze">')
  })

  it('routes add-on service management through governed offering relationships', () => {
    const catalog = source('../app/product-catalog/page.tsx')
    const studio = source('../app/product-studio/page.tsx')

    expect(catalog).toContain('href="/product-studio"')
    expect(studio).toContain('removeOfferingRelationship')
    expect(studio).toContain('addOfferingRelationship')
  })
})
