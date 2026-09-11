import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/aml/page.tsx'), 'utf8')

describe('AML scan truthfulness', () => {
  it('neither advertises a missing endpoint nor guesses deployment configuration', () => {
    const source = read()
    expect(source).not.toContain("'/api/v1/aml/scan'")
    expect(source).not.toContain('onClick={triggerScan}')
    // A successful case-list response proves neither that automated scanning is configured nor
    // that it is absent. The console must not turn an unknowable environment fact into guidance.
    expect(source).not.toContain('Automated AML scanning is not configured in this environment.')
    expect(source).not.toContain('Automatické AML skenování není v tomto prostředí nakonfigurováno.')
  })
})
