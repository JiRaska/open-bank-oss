import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/cloud-architecture/page.tsx'), 'utf8')

describe('cloud architecture controls accessibility contract', () => {
  it('names nodes and exposes selection state without dangling references', () => {
    expect(page).toContain('type="button"')
    expect(page).toContain('aria-label={`${t(...n.name)} — ${t(...m.label)}`}')
    expect(page).toContain('aria-pressed={active} aria-controls={active ? \'cloud-architecture-selection\' : undefined}')
    expect(page).toContain('id="cloud-architecture-selection" className="card" role="region"')
  })

  it('announces refresh progress and labels the detail close action', () => {
    expect(page).toContain('type="button"')
    expect(page).toContain('aria-busy={refreshing}')
    expect(page).toContain('aria-label={t(\'Zavřít detail architektury\', \'Close architecture details\')}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<X aria-hidden="true"')
    expect(page).toContain('<Info aria-hidden="true"')
  })
})
