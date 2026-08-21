import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('sanctions workflow controls expose state and busy semantics', () => {
  it('keeps tabs, scope actions, refresh and screening controls accessible', () => {
    const source = fs.readFileSync(path.join(process.cwd(), 'src/app/sanctions/page.tsx'), 'utf8')
    expect(source).toContain('role="group" aria-label={t(\'Sekce sankčního workflow\', \'Sanctions workflow sections\')}')
    expect(source).toContain('type="button" aria-pressed={tab === t.id}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('aria-label={t(\'Výběr všech sankčních listů\', \'Select sanctions lists\')}')
    expect(source).toContain('aria-busy={screening}')
    expect(source).toContain('aria-busy={refreshingAll}')
    expect(source).toContain('<Play size={14} aria-hidden="true" />')
  })
})
