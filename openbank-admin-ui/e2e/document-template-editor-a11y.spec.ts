// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const TEMPLATE = {
  id: 'template-42',
  code: 'LOAN_AGREEMENT',
  version: '1.0.0',
  name: 'Loan agreement',
  engine: 'HANDLEBARS',
  bodyHtml: '<p>Hello {{party.name}}</p>',
  locale: 'en',
  classification: 'confidential',
  status: 'DRAFT',
  updatedAt: '2026-08-31T12:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('template editor traps focus, closes with Escape and restores its trigger', async ({ page }) => {
  await page.route('**/api/svc/document-service/api/v1/documents/templates?limit=200', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([TEMPLATE]) }))
  await page.route('**/api/svc/document-service/api/v1/documents/templates/preview', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ renderedHtml: '<p>Hello Ada</p>' }) }))

  await page.goto('/document-templates')
  const trigger = page.getByRole('button', { name: /New Template|Nová šablona/ })
  await trigger.focus()
  await trigger.click()

  const dialog = page.getByRole('dialog', { name: /New Template|Nová šablona/ })
  await expect(dialog).toBeVisible()
  await expect(page.getByLabel(/Code|Kód/)).toBeFocused()

  await page.keyboard.press('Shift+Tab')
  await expect(page.getByRole('button', { name: /Close template editor|Zavřít editor šablony/ })).toBeFocused()

  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
})
