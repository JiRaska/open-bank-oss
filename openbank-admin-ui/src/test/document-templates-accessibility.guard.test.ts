import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/document-templates/page.tsx'), 'utf8')

describe('document template authoring accessibility', () => {
  it('exposes the authoring modal as a labelled modal dialog', () => {
    expect(page).toContain('role="dialog"')
    expect(page).toContain('aria-modal="true"')
    expect(page).toContain('aria-labelledby="template-editor-title"')
    expect(page).toContain('id="template-editor-title"')
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
    expect(page).toContain('role="tablist"')
    expect(page).toContain('role="tab"')
    expect(page).toContain('role="tabpanel"')
    expect(page).toContain('tabIndex={tab === tb.id ? 0 : -1}')
    expect(page).toContain("event.key === 'ArrowRight'")
    expect(page).toContain("event.key === 'ArrowLeft'")
    expect(page).toContain("event.key === 'Home'")
    expect(page).toContain("event.key === 'End'")
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
