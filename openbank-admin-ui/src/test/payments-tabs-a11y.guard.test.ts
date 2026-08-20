import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('payments tab navigation accessibility', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/payments/page.tsx'), 'utf8')

  it('names the scope group and exposes the active payment tab', () => {
    expect(source).toContain("aria-label={t('Rozsah plateb', 'Payment scope')}")
    expect(source).toContain('aria-pressed={isActive}')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-hidden="true"')
  })
})
