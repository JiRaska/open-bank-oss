import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('responsive page header contract', () => {
  const component = readFileSync(resolve(process.cwd(), 'src/components/ui/PageHeader.tsx'), 'utf8')
  const styles = readFileSync(resolve(process.cwd(), 'src/app/globals.css'), 'utf8')

  it('owns stable copy, title and action regions', () => {
    expect(component).toContain('className="page-header__copy"')
    expect(component).toContain('className="page-header__title-row"')
    expect(component).toContain('className="page-header__actions"')
  })

  it('wraps long hierarchy text and keeps phone actions reachable', () => {
    expect(styles).toContain('.breadcrumb { min-width: 0;')
    expect(styles).toContain('overflow-wrap: anywhere')
    expect(styles).toContain('.page-header__actions { width: 100%;')
    expect(styles).toContain('@media (pointer: coarse)')
    expect(styles).toContain('min-height: 44px')
  })
})
