import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('party detail tab accessibility', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/parties/[id]/page.tsx'), 'utf8')

  it('exposes the active PII detail section and hides tab icons', () => {
    expect(source).toContain("aria-label={t('Sekce detailu subjektu', 'Party detail sections')}")
    expect(source).toContain('aria-pressed={tab === item.id}')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
  })
})
