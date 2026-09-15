// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const component = readFileSync(path.resolve(__dirname, '../components/docs/Mermaid.tsx'), 'utf8')
const enhancer = readFileSync(path.resolve(__dirname, '../components/docs/MermaidEnhancer.tsx'), 'utf8')
const client = readFileSync(path.resolve(__dirname, '../lib/docs/mermaidClient.ts'), 'utf8')
const processView = readFileSync(path.resolve(__dirname, '../components/docs/ProcessView.tsx'), 'utf8')

describe('Mermaid presentation and security contract', () => {
  it('uses one shared error treatment in both render paths', () => {
    for (const source of [component, enhancer]) {
      expect(source).not.toMatch(/['"]#[0-9a-f]{6}\b|['"]#[0-9a-f]{3}(?![0-9a-f])/i)
      expect(source).toContain('var(--danger-border)')
      expect(source).toContain('var(--danger-bg)')
      expect(source).toContain('var(--danger-text)')
    }
  })

  it('keeps rendered SVG and error details behind sanitizing boundaries', () => {
    expect(component).toContain('DOMPurify.sanitize(svg')
    expect(enhancer).toContain('DOMPurify.sanitize(svg')
    expect(enhancer).toContain('escapeHtml(msg)')
    expect(enhancer).toContain('escapeHtml(src)')
  })

  it('loads one shared Mermaid runtime only after a diagram is requested', () => {
    expect(component).not.toMatch(/from ['"]mermaid['"]/u)
    expect(enhancer).not.toMatch(/from ['"]mermaid['"]/u)
    expect(component).toContain("from '@/lib/docs/mermaidClient'")
    expect(enhancer).toContain("from '@/lib/docs/mermaidClient'")
    expect(client.match(/import\('mermaid'\)/g)).toHaveLength(1)
    expect(client).toContain('if (instance) return instance')
    expect(processView).not.toContain("from '@/components/docs/Mermaid'")
    expect(processView).toContain("lazy(() => import('@/components/docs/Mermaid')")
    expect(processView).toContain('<Suspense fallback=')
  })
})
