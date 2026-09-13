import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/document-templates/page.tsx'), 'utf8')
const tabs = readFileSync(path.resolve(__dirname, '../components/ui/Tabs.tsx'), 'utf8')

describe('document template authoring accessibility', () => {
  it('exposes the authoring modal as a labelled modal dialog', () => {
    expect(page).toContain("import * as Dialog from '@radix-ui/react-dialog'")
    expect(page).toContain('<Dialog.Content')
    expect(page).toContain('<Dialog.Title')
    // Kept from this branch: main's set does not assert the dialog is DESCRIBED, only that it is
    // titled, and an alertdialog with no description is the case these guards exist to catch.
    // `<Dialog.Title asChild>` is NOT kept — main renders the title directly, so asserting
    // `asChild` would be a guard about a shape the page does not have.
    expect(page).toContain('<Dialog.Description')
    expect(page).toContain('onOpenAutoFocus={event =>')
    expect(page).toContain('onCloseAutoFocus={event =>')
    expect(page).toContain('onEscapeKeyDown={event =>')
    expect(page).toContain('aria-label={t(\'Zavřít editor šablony\'')
  })

  it('binds every template authoring field to its visible label', () => {
    for (const id of [
      'template-code', 'template-version', 'template-locale', 'template-name',
      'template-product-ref', 'template-classification', 'template-body-html', 'template-sample-data',
    ]) {
      expect(page).toContain(`htmlFor="${id}"`)
      expect(page).toContain(`id="${id}"`)
    }
    expect(page).toContain('role="alert"')
  })

  it('uses an accessible roving-focus tab pattern with permanently addressable panels', () => {
    expect(page).toContain('<Tabs')
    expect(tabs).toContain('role="tablist"')
    expect(tabs).toContain('role="tab"')
    expect(page).toContain('role="tabpanel"')
    expect(tabs).toContain('tabIndex={selected ? 0 : -1}')
    expect(tabs).toContain("event.key === 'ArrowRight'")
    expect(tabs).toContain("event.key === 'ArrowLeft'")
    expect(tabs).toContain("event.key === 'Home'")
    expect(tabs).toContain("event.key === 'End'")
    expect(page).toContain('hidden={tab !== \'templates\'}')
    expect(page).toContain('hidden={tab !== \'documents\'}')
    expect(page).toContain('id="template-status-filter"')
    expect(page).toContain('id="document-id"')
  })

  it('names every icon-only row action and keeps it a non-submit button', () => {
    expect(page).toContain("aria-label={canEdit ? t('Upravit šablonu', 'Edit template') : t('Zobrazit šablonu', 'View template')}")
    expect(page).toContain("aria-label={t('Publikovat šablonu', 'Publish template')}")
    expect(page).toContain("aria-label={t('Vyřadit šablonu', 'Retire template')}")
    expect(page).toContain("<button type=\"button\" className=\"btn btn-secondary btn-sm\"")
  })
})
