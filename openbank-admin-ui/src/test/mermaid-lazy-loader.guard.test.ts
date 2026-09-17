// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const renderer = readFileSync(path.resolve(__dirname, '../components/docs/Mermaid.tsx'), 'utf8')
const enhancer = readFileSync(path.resolve(__dirname, '../components/docs/MermaidEnhancer.tsx'), 'utf8')
const loader = readFileSync(path.resolve(__dirname, '../lib/docs/mermaidClient.ts'), 'utf8')

describe('Mermaid client loading boundary', () => {
  it('keeps the heavy renderer out of initial documentation bundles', () => {
    expect(renderer).not.toContain("from 'mermaid'")
    expect(enhancer).not.toContain("from 'mermaid'")
    expect(renderer).toContain("from '@/lib/docs/mermaidClient'")
    expect(enhancer).toContain("from '@/lib/docs/mermaidClient'")
    expect(loader).toContain("import('mermaid')")
  })
})
