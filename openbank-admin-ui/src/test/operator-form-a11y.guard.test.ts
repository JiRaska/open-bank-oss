// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = (name: string) => readFileSync(path.resolve(__dirname, `../app/${name}/page.tsx`), 'utf8')

describe('operator form accessibility contract', () => {
  it('names consent, identity-case and FX controls', () => {
    for (const label of [
      'Pohled souhlasů', 'ID grantee',
    ]) expect(page('consents')).toContain(`aria-label={t('${label}'`)
    for (const label of [
      'Verdikt případu identity', 'Propojit s existující party', 'Poznámka k rozhodnutí',
    ]) expect(page('identity-cases')).toContain(`aria-label={t('${label}'`)
    for (const label of [
      'Nákupní marže v procentech', 'Prodejní marže v procentech',
    ]) expect(page('fx')).toContain(`aria-label={t('${label}'`)
    expect(page('fx')).toContain('aria-label={t(`Override nákupního kurzu ${r.code}`')
    expect(page('fx')).toContain('aria-label={t(`Override prodejního kurzu ${r.code}`')
    expect(page('fx')).toContain('aria-label={t(`Hodina plánu ${s.id}`')
    expect(page('fx')).toContain('aria-label={t(`Minuta plánu ${s.id}`')
  })
})
