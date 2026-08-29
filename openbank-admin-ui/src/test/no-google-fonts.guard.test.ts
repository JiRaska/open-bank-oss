// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const layout = readFileSync(path.resolve(__dirname, '../app/layout.tsx'), 'utf8')
const globalsCss = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')
const proxy = readFileSync(path.resolve(__dirname, '../proxy.ts'), 'utf8')

const GOOGLE_FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com']

describe('operator shell serves fonts without a third-party request', () => {
  it('does not preconnect to Google Fonts from the root layout', () => {
    for (const host of GOOGLE_FONT_HOSTS) {
      expect(layout).not.toContain(host)
    }
  })

  it('keeps shell typography free of a CSS or build-time Google Fonts dependency', () => {
    expect(globalsCss).not.toMatch(/@import\s+url\(['"]?https:\/\/fonts\.googleapis\.com/)
    expect(layout).not.toMatch(/from\s+['"]next\/font\/google['"]/)
    for (const host of GOOGLE_FONT_HOSTS) {
      expect(globalsCss).not.toContain(host)
    }
  })

  it('no longer allowlists Google Fonts origins in the CSP', () => {
    for (const host of GOOGLE_FONT_HOSTS) {
      expect(proxy).not.toContain(host)
    }
  })
})
