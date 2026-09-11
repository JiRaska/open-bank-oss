// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/approvals/page.tsx'), 'utf8')
const rawColour = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])|rgba?\(/
const establishedDialogBackdrop = "background: 'rgba(15,23,42,.68)'"

describe('approval workbench theme contract', () => {
  it('uses shared tokens for every lifecycle and decision state', () => {
    expect(source.replace(establishedDialogBackdrop, '')).not.toMatch(rawColour)
    for (const tone of ['warning', 'success', 'danger', 'info']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
  })
})
