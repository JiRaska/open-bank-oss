// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const e2eRoot = path.resolve(__dirname, '../../e2e')

describe('E2E theme preference contract', () => {
  it('keeps every persisted theme write behind the shared production-contract helper', () => {
    const offenders = readdirSync(e2eRoot, { recursive: true })
      .filter(entry => String(entry).endsWith('.ts'))
      .flatMap(entry => {
        const relative = String(entry)
        const source = readFileSync(path.join(e2eRoot, relative), 'utf8')
        const keys = Array.from(source.matchAll(/localStorage\.setItem\(['"]([^'"]*theme[^'"]*)['"]/giu), match => match[1])
        if (relative === path.join('helpers', 'theme.ts')) {
          return keys.filter(key => key !== 'ob-admin-theme').map(key => `${relative}: wrong key ${key}`)
        }
        return keys.map(key => `${relative}: direct write ${key}`)
      })

    expect(offenders).toEqual([])
  })
})
