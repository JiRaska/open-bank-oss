import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/services/page.tsx'), 'utf8')

describe('services documentation discovery UX', () => {
  it('provides client-side search, group/status filters and honest empty state', () => {
    const source = read()
    expect(source).toContain('id="service-docs-query"')
    expect(source).toContain('aria-label={t(\'Filtry dokumentace služeb\', \'Service documentation filters\')}')
    expect(source).toContain('value={groupFilter}')
    expect(source).toContain('value={statusFilter}')
    expect(source).toContain('disabled={loading} onChange={event => setStatusFilter')
    expect(source).toContain('role="status" aria-live="polite"')
    expect(source).toContain('No services match the selected filters.')
    expect(source).toContain("/api/services/health")
    expect(source).toContain("/api/services/${c.id}/docs")
  })
})
