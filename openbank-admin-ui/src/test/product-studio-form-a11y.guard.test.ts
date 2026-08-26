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
    expect(page.match(/htmlFor="studio-[^"]+"/g)).toHaveLength(6)
    expect(page).toContain('aria-label={t(\'Kód nové specifikace\', \'New specification code\')}')
    expect(page).toContain('aria-label={t(\'Kód nové nabídky\', \'New offer code\')}')
  })

  it('exposes truthful progress and selection state for operator actions', () => {
    expect(page).toContain("aria-busy={activeMutation === 'create-specification'}")
    expect(page).toContain("aria-busy={activeMutation === 'create-offering'}")
    expect(page).toContain("aria-busy={activeMutation === 'create-revision'}")
    expect(page).toContain("aria-busy={activeMutation === 'save-draft'}")
    expect(page).toContain("aria-busy={activeMutation === 'publish'}")
    expect(page).toContain('aria-pressed={revisionId === item.id}')
    expect(page).toContain('aria-pressed={selection.offering.id === offeringId}')
  })
})
