import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

describe('approval decision controls', () => {
  it('exposes explicit, busy-aware maker-checker actions', () => {
    expect(page).toContain('type="button" aria-label={t(`Zkontrolovat a schválit návrh ${p.title}`')
    expect(page).toContain('type="button" aria-label={t(`Zkontrolovat a zamítnout návrh ${p.title}`')
    expect(page).toContain('aria-busy={flight.isRunning(`proposal:${p.id}`)}')
    expect(page).toContain('disabled={flight.isRunning(`proposal:${p.id}`)}')
    expect(page).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(page).toContain('role="alertdialog"')
    expect(page).toContain('<Dialog.Title')
    expect(page).toContain('<Dialog.Description')
    expect(page).toContain('onEscapeKeyDown={event => { if (busy) event.preventDefault() }}')
    expect(page).toContain('onPointerDownOutside={event => { if (busy) event.preventDefault() }}')
    expect(page).toContain('if (trigger?.isConnected && !trigger.disabled) trigger.focus()')
    expect(page).toContain('else fallbackFocusRef.current?.focus()')
    expect(page).toContain("disabled={busy || !reason.trim()}")
    expect(page).toContain("t('Důvod rozhodnutí (povinný, součást auditní stopy)', 'Decision reason (required, recorded in the audit trail)')")
    expect(page).toContain('<CheckCircle2 aria-hidden="true"')
    expect(page).toContain('<XCircle aria-hidden="true"')
    expect(page).toContain('type="button" onClick={load} disabled={loading} aria-busy={loading}')
  })
})
