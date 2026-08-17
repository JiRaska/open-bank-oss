// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

const page = (name: string) => readFileSync(path.resolve(__dirname, `../app/${name}/page.tsx`), 'utf8')

describe('oversight form accessibility contract', () => {
  it('names operator decision and investigation inputs', () => {
    expect(page('notifications')).toContain("aria-label={t('Důvod rozhodnutí notifikace'")
    expect(page('approvals')).toContain("aria-label={t('Důvod rozhodnutí návrhu'")
    expect(page('system/agent')).toContain('aria-label={t(`${name} parametr`')
    expect(page('iaops')).toContain("aria-label={t('Popis alertu pro RCA'")
  })
})
