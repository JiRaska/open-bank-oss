// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const layout = readFileSync(path.resolve(__dirname, '../app/layout.tsx'), 'utf8')
const globalsCss = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')
const proxy = readFileSync(path.resolve(__dirname, '../proxy.ts'), 'utf8')

const GOOGLE_FONT_HOSTS = ['fonts.googleapis.com', 'fonts.gstatic.com']

// The three files above are where the original defect lived. Naming them individually made
// the guard's scope a hand-kept list: a Google Fonts @import in ANY other stylesheet or
// component passed unnoticed (measured — a poisoned src/app/auth/login/login.module.css
// left this suite green). Scope is now DERIVED by sweeping the source tree.
const SOURCE_ROOT = path.resolve(__dirname, '..')
const SWEEP_EXTENSIONS = new Set(['.css', '.ts', '.tsx', '.js', '.jsx'])

function sweepSourceFiles(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === 'test') continue
      sweepSourceFiles(full, found)
    } else if (SWEEP_EXTENSIONS.has(path.extname(entry.name))) {
      found.push(full)
    }
  }
  return found
}

describe('operator shell serves fonts without a third-party request', () => {
  it('does not preconnect to Google Fonts from the root layout', () => {
    for (const host of GOOGLE_FONT_HOSTS) {
      expect(layout).not.toContain(host)
    }
  })

  it('self-hosts the shell typography via next/font instead of a CSS @import', () => {
    expect(globalsCss).not.toMatch(/@import\s+url\(['"]?https:\/\/fonts\.googleapis\.com/)
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

describe('no source file anywhere in the shell references a third-party font origin', () => {
  const files = sweepSourceFiles(SOURCE_ROOT)

  it('sweeps a non-trivial number of source files (guards against an empty sweep)', () => {
    expect(files.length).toBeGreaterThan(50)
  })

  it('finds no Google Fonts reference in any stylesheet or component', () => {
    const offenders = files
      .filter(f => GOOGLE_FONT_HOSTS.some(host => readFileSync(f, 'utf8').includes(host)))
      .map(f => path.relative(SOURCE_ROOT, f))

    expect(offenders).toEqual([])
  })
})
