import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

describe('approval decision controls', () => {
  it('exposes explicit, busy-aware maker-checker actions', () => {
    expect(page).toContain('type="button" aria-label={t(`Schválit návrh ${p.title}`')
    expect(page).toContain('type="button" aria-label={t(`Zamítnout návrh ${p.title}`')
    expect(page).toContain('aria-busy={flight.isRunning(`proposal:${p.id}`)}')
    expect(page).toContain('disabled={flight.isRunning(`proposal:${p.id}`)}')
    expect(page).toContain('<CheckCircle2 aria-hidden="true"')
    expect(page).toContain('<XCircle aria-hidden="true"')
    expect(page).toContain('type="button" onClick={load} disabled={loading} aria-busy={loading}')
  })
})
