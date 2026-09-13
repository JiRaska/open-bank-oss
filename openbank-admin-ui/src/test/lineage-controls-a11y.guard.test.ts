import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/lineage/page.tsx'), 'utf8')

describe('data lineage controls accessibility contract', () => {
  it('exposes labelled stateful filter groups and truthful action state', () => {
    expect(page).toContain('role="group" aria-label={t(\'Filtrování domén\', \'Domain filters\')}')
    expect(page).toContain('type="button" aria-pressed={domFilter === key}')
    expect(page).toContain('role="group" aria-label={t(\'Filtrování vztahů\', \'Relationship filters\')}')
    expect(page).toContain('type="button"')
    expect(page).toContain('aria-pressed={relFilter === r}')
    expect(page).toContain('aria-label={flow ? t(\'Pozastavit tok dat\', \'Pause data flow\') : t(\'Spustit tok dat\', \'Play data flow\')}')
  })

  it('marks decorative icons and refresh progress explicitly', () => {
    expect(page).toContain('aria-busy={isChecking}')
    expect(page).toContain('<RefreshCw aria-hidden="true"')
    expect(page).toContain('<Pause aria-hidden="true"')
    expect(page).toContain('<Play aria-hidden="true"')
  })
})
