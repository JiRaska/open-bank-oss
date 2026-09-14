import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/components/docs/BpmnView.tsx'), 'utf8')

describe('BPMN process discovery accessibility', () => {
  it('uses semantic theme tokens for every diagram colour', () => {
    const source = read()
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])/iu)
    for (const token of ['success', 'info', 'warning', 'danger', 'accent', 'map-edge-async-active']) {
      expect(source).toContain(`var(--${token}`)
    }
    expect(source).toContain("background: active === p.slug ? 'var(--selection-bg)' : 'var(--surface)'")
  })

  it('exposes localized process selection and honest live status semantics', () => {
    const source = read()
    expect(source).toContain('role="group" aria-label={t(\'Výběr obchodního procesu\', \'Business process selector\')}')
    expect(source).toContain('type="button" aria-pressed={active === p.slug}')
    expect(source).toContain('type="button"\n          onClick={checkServices}')
    expect(source).toContain('disabled={isChecking}')
    expect(source).toContain('aria-busy={isChecking}')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true" />')
    expect(source).toContain("t('Aktualizováno', 'Refreshed')")
    expect(source).toContain("fetch('/api/services/health')")
  })
})
