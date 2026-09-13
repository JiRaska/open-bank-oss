import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('ProcessView accessibility contract', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/components/docs/ProcessView.tsx'), 'utf8')

  it('names stateful controls and hides decorative icons', () => {
    expect(source).toContain("aria-label={t('Režim zobrazení procesu', 'Process view mode')}")
    expect(source).toContain("aria-label={t('Čočka zobrazení procesu', 'Process view lens')}")
    expect(source).toContain('aria-pressed={mode === m}')
    expect(source).toContain('aria-pressed={lens === id}')
    expect(source).toContain('aria-pressed={active}')
    expect(source).toContain('aria-label={n.name}')
    expect(source).toContain("aria-label={t('Zavřít technologické detaily', 'Close technology details')}")
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
  })
})
