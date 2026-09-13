import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/onboarding/page.tsx'), 'utf8')

describe('onboarding stage filter accessibility', () => {
  it('exposes a labelled group of stateful filter buttons', () => {
    const source = read()
    expect(source).toContain('role="group" aria-label={t(\'Filtr fází onboardingu\', \'Onboarding stage filters\')}')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-pressed={isActive}')
    expect(source).toContain('handleStageFilter(isActive ? \'\' : s)')
  })
})
