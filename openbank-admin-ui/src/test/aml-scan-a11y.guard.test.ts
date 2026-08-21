import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/aml/page.tsx'), 'utf8')

describe('AML scan truthfulness', () => {
  it('does not advertise an endpoint that the service does not expose', () => {
    const source = read()
    expect(source).not.toContain("'/api/v1/aml/scan'")
    expect(source).not.toContain('onClick={triggerScan}')
    expect(source).toContain('Automated AML scanning is not configured in this environment.')
    expect(source).toContain('Automatické AML skenování není v tomto prostředí nakonfigurováno.')
  })
})
