import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(join(process.cwd(), 'src/app/product-studio/page.module.css'), 'utf8')
const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

describe('Product Studio semantic theme contract', () => {
  it('keeps page-local colour literals out of the authoring workflow', () => {
    expect(styles).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(styles).not.toMatch(/rgba?\(/i)
  })

  it('uses shared surface, text, state and accent ownership', () => {
    for (const token of ['surface', 'text-primary', 'text-secondary', 'border', 'accent', 'success', 'warning', 'danger', 'info']) {
      expect(styles).toContain(`var(--${token}`)
    }
    expect(styles).toContain('color-mix(in srgb')
  })

  it('preserves truncation and pre-wrapped feedback behavior', () => {
    expect(styles.match(/white-space: nowrap/g)).toHaveLength(4)
    expect(styles).toContain('white-space: pre-wrap')
  })

  it('keeps compact authoring metadata at a readable 10px floor', () => {
    for (const selector of [
      '.marketGrid label',
      '.bundleProposal small',
      '.bundleProposal em',
      '.readinessRow b',
      '.previewContextGrid label',
      '.selectionCopy small',
      '.explanationTrace code',
    ]) {
      expect(styles).toMatch(new RegExp(`${escapeRegExp(selector)} \\{[^}]*font-size: 10px;`))
    }
    expect(styles).not.toMatch(/font-size:\s*[7-9]px/)
  })
})
