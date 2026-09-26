// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const source = fs.readFileSync(path.resolve(process.cwd(), 'src/app/business-onboarding/page.tsx'), 'utf8')

describe('business onboarding accessibility and state', () => {
  it('labels every operator control and reports its busy state', () => {
    expect(source).toContain('aria-busy={busy}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-live="polite"')
    expect(source).toContain('role="alert"')
    for (const label of [
      'Počet vyžadovaných podpisů',
      'Funkce, které musí podepsat',
      'Potvrdit způsob zastoupení',
      'Zobrazit historii potvrzení',
      'Obnovit frontu firemních případů',
    ]) {
      expect(source).toContain(`aria-label={t('${label}'`)
    }
    // Decorative icons must not be announced; the button's own aria-label carries the meaning.
    expect(source).toContain('<Check size={14} aria-hidden="true"')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
  })

  it('guards each mutation with the single-flight lock the fleet uses', () => {
    expect(source).toContain("import { useSingleFlight, wasSkipped } from '@/lib/mutations/singleFlight'")
    expect(source).toContain('flight.run(`kyb-attest:${scheme}:${identifier}`')
    expect(source).toContain('if (wasSkipped(outcome)) return false')
  })
})
