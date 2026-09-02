import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/service-map/page.tsx'), 'utf8')

describe('service architecture map controls accessibility contract', () => {
  it('labels the filter and toggle groups and exposes state', () => {
    expect(page).toContain('role="group" aria-label={t(\'Filtrování skupin služeb\', \'Service group filters\')}')
    expect(page).toContain('type="button" aria-pressed={filter === key}')
    expect(page).toContain('role="group" aria-label={t(\'Ovládání mapy služeb\', \'Service map controls\')}')
    expect(page).toContain('type="button" aria-label={c.label}')
    expect(page).toContain('aria-pressed={c.on}')
  })

  it('announces health refresh progress and hides decorative icons', () => {
    expect(page).toContain('type="button" aria-busy={isChecking}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<Pause aria-hidden="true"')
    expect(page).toContain('<Play aria-hidden="true"')
    expect(page).toContain('<Server aria-hidden="true"')
    expect(page).toContain('<Cloud aria-hidden="true"')
  })
})
