// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// ADR-0352 phase 5b: the Studio IR editor lints through the catalog and saves the DRAFT only.
const SPEC = { id: '10000000-0000-0000-0000-000000000001', code: 'SAV', schemaRef: { id: 'org.openbank.banking.deposit', version: 2 }, createdAt: '2026-10-29T00:00:00Z', revision: 0 }
const OFFERING = { id: '20000000-0000-0000-0000-000000000001', specificationId: SPEC.id, code: 'SAV_CZ', market: {}, revision: 0 }
const IR = { irVersion: 1, productCode: 'SAV', currency: 'EUR', hooks: {} }
const REVISION = {
  id: '30000000-0000-0000-0000-000000000001', offeringId: OFFERING.id, number: 1, schemaRef: SPEC.schemaRef, state: 'DRAFT',
  content: { name: { en: 'Savings' }, attributes: {}, prices: [], eligibility: [], relationships: [], documentCodes: [], behaviour: IR },
  makerId: 'maker', contentHash: 'a'.repeat(64), createdAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:00Z',
  effectiveFrom: null, effectiveTo: null, revision: 3,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('Studio IR editor: server lint gates the DRAFT save, and nothing publishes or signs', async ({ page }) => {
  const writes: Array<{ method: string; path: string; ifMatch?: string; body: unknown }> = []
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/auth/')) return route.continue()
    const method = route.request().method()
    const raw = route.request().postData()
    let payload: Record<string, unknown> | undefined
    try { payload = raw ? JSON.parse(raw) as Record<string, unknown> : undefined } catch { payload = undefined }
    const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
    if (method !== 'GET') writes.push({ method, path: url.pathname, ifMatch: route.request().headers()['if-match'], body: payload })
    if (url.pathname.endsWith('/api/v2/product-ir/lint')) {
      const ir = (payload as { ir: Record<string, unknown> }).ir
      return json(ir.bogus
        ? { valid: false, problems: [{ path: '/bogus', message: 'unknown key' }] }
        : { valid: true, productCode: 'SAV', irVersion: 1, revisionHash: 'c'.repeat(64), canonicalIr: '{}', problems: [] })
    }
    if (url.pathname.endsWith('/api/v2/product-types')) return json([])
    if (url.pathname.endsWith('/api/v2/specifications')) return json([SPEC])
    if (url.pathname.endsWith('/api/v2/offerings')) return json([OFFERING])
    if (url.pathname.endsWith(`/offerings/${OFFERING.id}/revisions`)) return json([REVISION])
    if (method === 'PUT' && url.pathname.endsWith(`/revisions/${REVISION.id}`)) return json(REVISION)
    return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
  })

  await page.goto('/product-studio')
  await page.locator('#studio-offering').selectOption(OFFERING.id)
  const editor = page.getByTestId('product-ir-editor')
  await expect(editor).toBeVisible()
  const save = editor.getByRole('button', { name: /Uložit IR do draftu|Save IR to draft/ })
  const lint = editor.getByRole('button', { name: /Zkontrolovat IR|Lint IR/ })
  const doc = editor.getByRole('textbox', { name: /IR dokument|IR document/ })

  // Negative first: an IR the server refuses shows its problems and cannot be saved.
  await doc.fill(JSON.stringify({ ...IR, bogus: true }))
  await lint.click()
  await expect(editor.getByTestId('ir-lint-problems')).toContainText('/bogus')
  await expect(save).toBeDisabled()

  await doc.fill(JSON.stringify({ ...IR, currency: 'CZK' }))
  await expect(save).toBeDisabled()
  await lint.click()
  await expect(editor.getByTestId('ir-lint-valid')).toContainText('c'.repeat(64))
  await save.click()
  await expect.poll(() => writes.filter(w => w.method === 'PUT').length).toBe(1)

  const put = writes.find(w => w.method === 'PUT')!
  expect(put.ifMatch).toBe('"3"')
  expect((put.body as { behaviour: unknown }).behaviour).toEqual({ ...IR, currency: 'CZK' })
  expect(writes.some(w => /publish|approv|sign/.test(w.path))).toBe(false)
})
