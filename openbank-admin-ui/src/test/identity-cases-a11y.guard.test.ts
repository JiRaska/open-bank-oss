import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/identity-cases/page.tsx'), 'utf8')

describe('identity cases four-eyes accessibility', () => {
  it('keeps decision and refresh controls explicit and stateful', () => {
    const source = read()
    expect(source).toContain('role="alertdialog"')
    expect(source).toContain('aria-busy={busy}')
    expect(source).toContain('trapDialogFocus(event, dialogRef.current)')
    expect(source).toContain('if (succeeded) setDecisionIntent(null)')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('className="btn btn-secondary"')
    expect(source).toContain('<Check size={14} aria-hidden="true"')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain('method: \'POST\'')
  })
})
