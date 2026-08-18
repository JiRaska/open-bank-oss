import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('compact operational filters are named and stateful', () => {
  it('keeps cron controls and catalog filters accessible', () => {
    const sanctions = fs.readFileSync(path.join(process.cwd(), 'src/app/sanctions/page.tsx'), 'utf8')
    expect(sanctions).toContain('id={`sanctions-cron-${list.id}-hour`}')
    expect(sanctions).toContain('id={`sanctions-cron-${list.id}-minute`}')
    expect(sanctions).toContain('aria-pressed={days.includes(d)}')

    const catalog = fs.readFileSync(path.join(process.cwd(), 'src/app/product-catalog/page.tsx'), 'utf8')
    expect(catalog).toContain('id={`catalog-filter-${f.id}`}')
    for (const id of ['type', 'status', 'visibility']) expect(catalog).toContain(`id: '${id}'`)
    expect(catalog).toContain('aria-label={f.label}')
  })
})
