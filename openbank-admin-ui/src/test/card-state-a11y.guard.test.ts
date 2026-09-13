import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('card and row state accessibility contract', () => {
  it('keeps expandable documentation layers and audit rows truthful', () => {
    const cluster = read('app/docs/cluster/page.tsx')
    const audit = read('app/audit/page.tsx')
    expect(cluster).toContain('role="button" tabIndex={0} aria-expanded={on}')
    expect(cluster).toContain("e.key === 'Enter'")
    expect(cluster).toContain("e.key === ' '")
    expect(cluster).toContain('setActiveLayer(current => current === l.id ? null : l.id)')
    expect(audit).toContain("expanded === e.id ? t('Sbalit auditní událost'")
    expect(audit).toContain("expanded === e.id ? null : e.id")
  })
})
