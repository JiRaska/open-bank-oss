// SPDX-License-Identifier: Apache-2.0

import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const appDir = path.join(process.cwd(), 'src/app')

function htmlRoutes(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) return htmlRoutes(file)
    return entry.isFile() && (entry.name === 'page.tsx' || entry.name === 'layout.tsx') ? [file] : []
  })
}

describe('nonce CSP on HTML routes', () => {
  it('keeps pages dynamic so bootstrap scripts receive the per-request nonce', () => {
    const rootLayout = readFileSync(path.join(appDir, 'layout.tsx'), 'utf8')
    expect(rootLayout).toContain('await headers()')
    for (const file of htmlRoutes(appDir)) {
      const source = readFileSync(file, 'utf8')
      expect(source, file).not.toMatch(/export\s+const\s+dynamic\s*=\s*['"]force-static['"]/)
    }
  })
})
