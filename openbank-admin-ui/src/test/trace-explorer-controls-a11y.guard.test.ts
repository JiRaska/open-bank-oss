import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/observability/traces/page.tsx'), 'utf8')

describe('trace explorer controls accessibility contract', () => {
  it('names the refresh action and exposes the trace selection state', () => {
    expect(page).toContain('type="button" onClick={loadTraces} className="btn btn-secondary" disabled={loading} aria-busy={loading}')
    expect(page).toContain('role="region" aria-label={t(\'Seznam posledních tras\', \'Recent traces list\')}')
    expect(page).toContain('type="button" onClick={() => openTrace(tr.traceID)} aria-pressed={active}')
    expect(page).toContain('aria-label={`${tr.rootServiceName ?? t(\'neznámá služba\', \'unknown service\')} — ${tr.rootTraceName ?? tr.traceID.slice(0, 12)}`}')
  })

  it('keeps loading announcements and decorative icons out of the accessibility tree', () => {
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<ChevronRight aria-hidden="true"')
    expect(page).toContain('<Activity aria-hidden="true"')
    expect(page).toContain('<Clock aria-hidden="true"')
  })
})
