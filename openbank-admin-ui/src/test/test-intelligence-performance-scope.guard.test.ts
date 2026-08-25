import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = () => readFileSync(path.join(process.cwd(), 'src/app/system/tests/page.tsx'), 'utf8')

describe('Test Intelligence performance coverage scope', () => {
  it('keeps declared, executed, and undeclared performance scope visible', () => {
    const source = read()

    expect(source).toContain("const declaredComponents = new Set(report.performance.flatMap(row => row.component ? [row.component] : []))")
    expect(source).toContain("const executed = report.performance.filter(row => row.state === 'passed' || row.state === 'failed').length")
    expect(source).toContain("const undeclared = report.components.filter(component => !declaredComponents.has(component.component)).length")
    expect(source).toContain("t('Performance evidence scope')")
    expect(source).toContain("t('components without a declared scenario')")
  })
})
