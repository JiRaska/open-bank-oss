import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('day-end controls expose workflow state', () => {
  it('keeps closing tabs, refresh and catch-up actions named and busy-aware', () => {
    const source = fs.readFileSync(path.join(process.cwd(), 'src/app/day-end/page.tsx'), 'utf8')
    expect(source).toContain('role="group" aria-label={t(\'Typ závěrky\', \'Closing type\')}')
    expect(source).toContain('aria-pressed={isActive}')
    expect(source).toContain('aria-busy={refreshing}')
    expect(source).toContain('aria-label={t(\'Obnovit denní závěrku\', \'Refresh day-end close\')}')
    expect(source).toContain('aria-label={t(\'Obnovit měsíční závěrku\', \'Refresh month-end close\')}')
    expect(source).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(source).toContain('<Dialog.Root open onOpenChange={open => { if (!open && !busy) onCancel() }}>')
    expect(source).toContain('<Dialog.Title')
    expect(source).toContain('<Dialog.Description')
    expect(source).toContain('aria-busy={busy}')
    expect(source).toContain('onCloseAutoFocus={event => {')
    expect(source).toContain('triggerCloseFocusOverrideRef.current = workspaceRef.current')
    expect(source).toContain('onEscapeKeyDown={event => { if (busy) event.preventDefault() }}')
    expect(source).not.toContain('trapDialogFocus')
    expect(source).toContain('<Play size={13} aria-hidden="true"')
  })
})
