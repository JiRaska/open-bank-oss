// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'fs'
import path from 'path'

describe('IAOps agent capability truthfulness', () => {
  it('does not expose a fake interactive HITL trigger', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')

    expect(source).toContain('Analysis is not connected to the HITL backend yet')
    expect(source).toContain('Analýza zatím není připojená k HITL backendu')
    expect(source).not.toContain("alert(t('Funkce přijde v P4 (HITL backend)'")
    expect(source).not.toContain("{t('Spustit analýzu', 'Trigger Analysis')}")
  })
})
