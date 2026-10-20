// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = (name: string) => readFileSync(path.resolve(__dirname, `../app/${name}/page.tsx`), 'utf8')

describe('operator search accessibility contract', () => {
  it('gives every migrated search field a localized accessible name', () => {
    const expected: Record<string, string> = {
      swift: 'Hledat SWIFT zprávy',
      sanctions: 'Hledat sankční kontroly',
      'product-catalog': 'Hledat v katalogu produktů',
      audit: 'Technické ID (pokročilé)',
    }
    for (const [route, label] of Object.entries(expected)) {
      expect(page(route)).toContain(`aria-label={t('${label}'`)
    }
    expect(page('pid')).toContain("aria-label={t('Přesná hodnota identifikátoru', 'Exact identifier value')}")
    expect(page('pid')).toContain("{t('Typ identifikátoru', 'Identifier type')}")
    expect(page('pid')).toContain('onSubmit={submitLookup}')
  })
})
