// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('skip-link theme contrast', () => {
  it('uses the selected-control foreground/background pair in every theme', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/app/globals.css'), 'utf8')
    const rule = css.slice(css.indexOf('.ob-skip-link {'), css.indexOf('.ob-mobile-nav-overlay'))

    expect(rule).toContain('background: var(--selection-bg)')
    expect(rule).toContain('color: var(--text-inverse)')
    expect(rule).toContain('outline: 3px solid var(--text-primary)')
    expect(rule).not.toContain('color: #fff')
  })
})
