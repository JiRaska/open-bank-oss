import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/cluster/page.tsx'), 'utf8')

describe('cluster topology controls accessibility contract', () => {
  it('exposes controlled anatomy and namespace disclosures', () => {
    expect(page).toContain('type="button"')
    expect(page).toContain('aria-expanded={on} aria-controls={on ? `cluster-anatomy-panel-${st.id}` : undefined}')
    expect(page).toContain('id={`cluster-anatomy-panel-${st.id}`} role="region"')
    expect(page).toContain('const panelId = `cluster-ns-panel-${nsItem.name.replace(/[^a-zA-Z0-9_-]/g, \'-\')}`')
    expect(page).toContain('aria-expanded={on} aria-controls={on ? panelId : undefined}')
    expect(page).toContain('id={panelId} role="region" aria-label={nsItem.name}')
  })

  it('announces refresh progress and hides decorative icons', () => {
    expect(page).toContain('type="button" aria-busy={loading}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<BadgeCheck aria-hidden="true"')
    expect(page).toContain('<ChevronRight aria-hidden="true"')
  })
})
