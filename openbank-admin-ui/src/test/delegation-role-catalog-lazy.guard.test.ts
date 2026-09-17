// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/delegations/page.tsx'), 'utf8')
const boundary = readFileSync(path.resolve(__dirname, '../components/delegations/LazyRoleCatalog.tsx'), 'utf8')

describe('delegation role catalog bundle boundary', () => {
  it('keeps preset management and its dialogs out of the primary lookup bundle', () => {
    expect(page).toContain("from '@/components/delegations/LazyRoleCatalog'")
    expect(page).not.toContain("from '@/components/delegations/RoleCatalog'")
    expect(boundary).toContain("import('./RoleCatalog')")
    expect(boundary).toContain("rootMargin: '600px 0px'")
    expect(boundary).toContain('}, 1_500)')
    expect(boundary).toContain('nearViewport ? <RoleCatalog /> : null')
  })
})
