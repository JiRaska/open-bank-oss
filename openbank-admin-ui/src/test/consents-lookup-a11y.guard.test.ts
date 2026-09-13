import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/consents/page.tsx'), 'utf8')

describe('consent lookup accessibility', () => {
  it('keeps lookup actions explicit and hides decorative icon', () => {
    const source = read()
    expect(source).toContain('type="button"\n              aria-busy={loading}\n              onClick={() => lookup()}')
    expect(source).toContain('<Search size={15} aria-hidden="true" />')
    expect(source).toContain('type="button"\n              aria-busy={loading}\n              onClick={() => { setTerm(MARKETING_GRANTEE); void lookup(MARKETING_GRANTEE) }}')
    expect(source).toContain('lookup(MARKETING_GRANTEE)')
  })
})
