import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('DevOps metrics refresh contract', () => {
  it('exposes busy semantics without changing the metrics loader', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/devops/page.tsx'), 'utf8')

    expect(source).toContain('type="button"')
    expect(source).toContain('disabled={loading}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit DevOps metriky', 'Refresh DevOps metrics')}")
    expect(source).toContain('<RefreshCw size={13} aria-hidden="true"')
    expect(source).toContain('const load = useCallback')
  })
})
