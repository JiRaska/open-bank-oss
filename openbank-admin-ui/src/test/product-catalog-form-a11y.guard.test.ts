import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('product catalog editor accessibility', () => {
  it('associates every editable catalog field with its label', () => {
    const page = readFileSync(resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')
    for (const field of ['code', 'type', 'name', 'description', 'currency', 'status', 'version', 'base-rate', 'valid-from']) {
      expect(page).toContain(`htmlFor="catalog-${field}"`)
      expect(page).toContain(`id="catalog-${field}"`)
    }
  })

  it('uses the shared modal contract and restores focus deliberately', () => {
    const page = readFileSync(resolve(__dirname, '../app/product-catalog/page.tsx'), 'utf8')

    expect(page).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(page).toContain('<Dialog.Title')
    expect(page).toContain('<Dialog.Description')
    expect(page).toContain('onOpenAutoFocus=')
    expect(page).toContain('onCloseAutoFocus=')
    expect(page).toContain('editorTriggerRef.current')
    expect(page).toContain('catalogWorkspaceRef.current')
    expect(page).toContain('onEscapeKeyDown=')
    expect(page).toContain('if (saving) event.preventDefault()')
    expect(page).toContain('role="alert"')
  })
})
