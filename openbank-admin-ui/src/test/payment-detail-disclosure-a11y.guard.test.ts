import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('payment detail raw payload disclosure accessibility', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/payments/[id]/page.tsx'), 'utf8')

  it('exposes disclosure state and a labelled payload region', () => {
    expect(source).toContain('aria-expanded={showRaw}')
    expect(source).toContain("aria-controls={showRaw ? 'payment-raw-payload' : undefined}")
    expect(source).toContain('id="payment-raw-payload"')
    expect(source).toContain('role="region"')
    expect(source).toContain("aria-label={showRaw ? t('Skrýt surová data platby', 'Hide raw payment payload') : t('Zobrazit surová data platby', 'Show raw payment payload')}")
    expect(source).toContain('aria-hidden="true"')
    expect(source).toContain('type="button"')
  })
})
