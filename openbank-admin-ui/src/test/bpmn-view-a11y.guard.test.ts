import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/components/docs/BpmnView.tsx'), 'utf8')

describe('BPMN process discovery accessibility', () => {
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
