import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/system/tests/page.tsx'), 'utf8')

describe('system code quality accessibility', () => {
  it('exposes stateful view filters and a busy refresh action', () => {
    const source = read()
    expect(source).toContain('type="button" onClick={load} disabled={testLoading || qualityLoading} aria-busy={testLoading || qualityLoading}')
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('role="group" aria-label={t(\'Přepínač pohledů kvality kódu\', \'Code quality view\')}')
    expect(source).toContain('type="button"\n            aria-pressed={tab === tabDef.id}')
    expect(source).toContain('<span aria-hidden="true">{tabDef.icon}</span>')
  })

  it('does not encode synthetic journey coverage by colour alone', () => {
    const source = read()
    expect(source).toContain("t('Sledováno', 'Covered')")
    expect(source).toContain("t('Nesledováno', 'Unwatched')")
    expect(source).toContain('aria-label={`${service.component}: ${stateLabel}${detail ? `. ${detail}` : \'\'}`}')
    expect(source).toContain(' · <strong>{stateLabel}</strong>')
  })

  it('explains unmatched Testcontainers lifecycle evidence in text', () => {
    const source = read()
    expect(source).toContain('const unmatchedStarts = started.length - stopped.length')
    expect(source).toContain('const impossibleStops = stopped.length > started.length')
    expect(source).toContain("started.length === 0 || impossibleStops || unmatchedStarts > 0 ? 'unknown'")
    expect(source).toContain('role="status"')
    expect(source).toContain('aggregate evidence alone proves neither a leak nor cleanup.')
    expect(source).toContain('opaque resource-manager scopes')
    expect(source).toContain('Inconsistent lifecycle evidence: more stops than starts.')
  })

  it('uses the shared semantic badge vocabulary for evidence states', () => {
    const source = read()

    expect(source).toContain("import { PageHeader, StatusBadge as SharedStatusBadge, TONE_TEXT_CLASS, type Tone } from '@/components/ui'")
    expect(source).toContain('function evidenceTone(state: EvidenceState): Tone')
    expect(source).toContain("case 'passed': return 'success'")
    expect(source).toContain("case 'failed': return 'danger'")
    expect(source).toContain("case 'blocked': return 'accent'")
    expect(source).toContain('<SharedStatusBadge status={state} tone={evidenceTone(state)} label={state} withDot />')
    expect(source).not.toContain('STATE_COLOR')
  })

  it('keeps the component evidence matrix ahead of detailed control rows', () => {
    const source = read()
    const matrix = source.indexOf("t('Matice důkazů komponent', 'Component evidence matrix')")
    const controls = source.indexOf("t('Deterministické povinné kontroly', 'Deterministic required controls')")

    expect(matrix).toBeGreaterThan(-1)
    expect(controls).toBeGreaterThan(matrix)
    expect(source).toContain('One row per component: scan across test kinds; money-path components are first.')
  })

  it('keeps platform capability boundaries as a scanable matrix', () => {
    const source = read()

    expect(source).toContain("t('Matice hranic schopností', 'Platform capability boundary matrix')")
    expect(source).toContain("t('Hranice / blokátor', 'Boundary / blocker')")
    expect(source).toContain("<th style={thStyle}>Evidence</th>")
    expect(source).toContain('One capability per row: state, actual blocker and verifiable source stay side by side.')
  })
})
