// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = readFileSync(path.resolve(__dirname, '../app/product-studio/page.tsx'), 'utf8')

describe('product studio form accessibility contract', () => {
  it('associates standalone labels and editor controls', () => {
    const controls = [
      'studio-specification',
      'studio-new-spec-schema',
      'studio-new-spec-code',
      'studio-offering',
      'studio-new-offering-code',
      'studio-relationship-kind',
      'studio-relationship-target',
      'studio-publish-reason',
    ]
    for (const id of controls) {
      expect(page).toContain(`id="${id}"`)
    }
    // The approval reason is a seventh labelled control. Check the exact targets so a new
    // label cannot make the count pass while an existing control loses its association.
    const labelledControls = controls.filter(id => id !== 'studio-new-spec-code' && id !== 'studio-new-offering-code')
    labelledControls.push('studio-pension-approval-reason')
    expect(page.match(/htmlFor="studio-[^"]+"/g)?.map(label => label.slice('htmlFor="'.length, -1)).sort()).toEqual(labelledControls.sort())
    expect(page).toContain('id="studio-pension-approval-reason"')
    expect(page).toContain('aria-label={t(\'Kód nové specifikace\', \'New specification code\')}')
    expect(page).toContain('aria-label={t(\'Kód nové nabídky\', \'New offer code\')}')
  })
})
