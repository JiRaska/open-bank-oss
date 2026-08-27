import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = () => readFileSync(path.join(process.cwd(), 'src/app/system/tests/page.tsx'), 'utf8')
const root = () => path.resolve(process.cwd(), '..')

describe('Test Intelligence performance coverage scope', () => {
  it('keeps declared, executed, and undeclared performance scope visible', () => {
    const source = read()

    expect(source).toContain("const declaredComponents = new Set(report.performance.flatMap(row => row.component ? [row.component] : []))")
    expect(source).toContain("const executed = report.performance.filter(row => row.state === 'passed' || row.state === 'failed').length")
    expect(source).toContain("const undeclared = report.components.filter(component => !declaredComponents.has(component.component)).length")
    expect(source).toContain("t('Rozsah výkonnostních důkazů', 'Performance evidence scope')")
    expect(source).toContain("t('komponent bez deklarovaného scénáře', 'components without a declared scenario')")
  })

  it('does not describe the blocked money-path smoke as a running scheduled measurement', () => {
    const scenarios = readFileSync(path.join(root(), 'perf/scenarios.yaml'), 'utf8')
    const workflow = readFileSync(path.join(root(), '.github/workflows/perf-gate.yml'), 'utf8')

    expect(scenarios).toContain('id: money-path-smoke')
    expect(scenarios).toContain('execution_mode: planned-read-only-sandbox')
    expect(scenarios).toContain('blocker: No isolated money-path target, dedicated runner, and verified least-privilege read identity')
    expect(workflow).toContain('DECLARED = ["openbank-product-catalog"]')
  })

  it('refuses to turn an authorization rejection into money-path latency evidence', () => {
    const smoke = readFileSync(path.join(root(), 'perf/k6/money-path-smoke.js'), 'utf8')

    expect(smoke).toContain('http.expectedStatuses(200)')
    expect(smoke).not.toContain('http.expectedStatuses(200, 401)')
    expect(smoke).toContain('"checks": ["rate==1.0"]')
    expect(smoke).toContain('"ledger journals 200"')
    expect(smoke).toContain('"txn list 200"')
    expect(smoke).not.toContain('"ledger journals 200|401"')
    expect(smoke).not.toContain('"txn list 200|401"')
  })

  it('keeps cross-layer evidence gaps visible from the primary operator view', () => {
    const source = read()

    expect(source).toContain("function EvidenceGapQueue")
    expect(source).toContain("t('Fronta skutečných mezer důkazů', 'Real evidence-gap queue')")
    expect(source).toContain("row.plan?.blocker")
    expect(source).toContain("platform.runtime !== 'passed'")
    expect(source).toContain('<EvidenceGapQueue report={report} selectTab={setTab} />')
    expect(source).toContain("{gaps.map(gap =>")
    expect(source).not.toContain('gaps.slice(0, 12)')
  })
})
