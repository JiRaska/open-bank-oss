import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (route: string) => readFileSync(resolve(process.cwd(), 'src/app', route, 'page.tsx'), 'utf8')

describe('FinOps and trace observability locale contract', () => {
  it('uses active locale for refresh, money and cost displays', () => {
    const finops = read('finops')
    const traces = read('observability/traces')
    expect(finops).not.toMatch(/toLocale(?:String|DateString|TimeString)\(\)/)
    expect(finops).not.toMatch(/toLocaleString\(undefined/)
    expect(finops).toMatch(/const locale = language === 'cs' \? 'cs-CZ' : 'en-GB'/)
    expect(traces).not.toMatch(/toLocale(?:String|DateString|TimeString)\(\)/)
    expect(traces).toMatch(/toLocaleTimeString\(dateLocale\)/)
  })
})
