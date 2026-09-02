// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (file: string) => fs.readFileSync(path.join(process.cwd(), file), 'utf8')
const grant = read('src/components/delegations/GrantView.tsx')
const lifecycle = read('src/components/infra/LifecycleStrip.tsx')
const grantTable = read('src/components/delegations/GrantTable.tsx')
const effectiveAccess = read('src/components/delegations/EffectiveAccess.tsx')
const detail = read('src/app/delegations/[id]/page.tsx')
const infrastructure = read('src/app/infrastructure/page.tsx')

describe('shared display components use the active locale', () => {
  it('does not hard-code Czech formatting in shared components', () => {
    expect(grant).not.toContain("toLocaleString('cs-CZ'")
    expect(lifecycle).not.toContain("toLocaleDateString('cs-CZ'")
    expect(grant).toContain('toLocaleString(locale)')
    expect(lifecycle).toContain('toLocaleDateString(locale')
  })

  it('passes page language into every shared formatter consumer', () => {
    expect(grantTable).toContain('grantConditions(g, language)')
    expect(effectiveAccess).toContain("language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(effectiveAccess).toContain('formatCeiling(limit, locale)')
    expect(detail).toContain('formatCeiling(grant.monthlyLimit, numberLocale)')
    expect(infrastructure).toContain('dateLocale={dateLocale}')
  })
})
