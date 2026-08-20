import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/security/page.tsx'), 'utf8')

describe('security severity filter accessibility', () => {
  it('exposes a labelled group of stateful filter buttons', () => {
    const source = read()
    expect(source).toContain('role="group" aria-label={t(\'Filtr závažnosti nálezů\', \'Finding severity filters\')}')
    expect(source).toContain('type="button" aria-pressed={filter === f}')
    expect(source).toContain('setFilter(f)')
  })
})
