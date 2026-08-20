import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/aml/page.tsx'), 'utf8')

describe('AML scan action accessibility', () => {
  it('exposes localized action state without changing the scan handler', () => {
    const source = read()
    expect(source).toContain('type="button" aria-label={t(\'Spustit AML kontrolu\', \'Run AML scan\')} aria-busy={scanning}')
    expect(source).toContain('<Play size={13} aria-hidden="true"')
    expect(source).toContain('onClick={triggerScan}')
    expect(source).toContain('disabled={scanning || !serviceReachable}')
  })
})
