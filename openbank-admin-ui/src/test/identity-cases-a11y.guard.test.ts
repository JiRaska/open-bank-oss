import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/identity-cases/page.tsx'), 'utf8')

describe('identity cases four-eyes accessibility', () => {
  it('keeps decision and refresh controls explicit and stateful', () => {
    const source = read()
    expect(source).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(source).toContain('<Dialog.Root open onOpenChange={open => { if (!open && !busy) onCancel() }}>')
    expect(source).toContain('<Dialog.Content')
    expect(source).toContain('<Dialog.Title')
    expect(source).toContain('<Dialog.Description')
    expect(source).toContain('aria-busy={busy}')
    expect(source).toContain('onCloseAutoFocus={event => {')
    expect(source).toContain('onEscapeKeyDown={event => { if (busy) event.preventDefault() }}')
    expect(source).toContain('decisionCloseFocusOverrideRef.current = closeFocusFallbackRef.current')
    expect(source).not.toContain('trapDialogFocus')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('className="btn btn-secondary"')
    expect(source).toContain('<Check size={14} aria-hidden="true"')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain('method: \'POST\'')
  })
})
