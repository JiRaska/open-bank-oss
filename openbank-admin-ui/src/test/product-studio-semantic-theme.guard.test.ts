import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(join(process.cwd(), 'src/app/product-studio/page.module.css'), 'utf8')

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
})
