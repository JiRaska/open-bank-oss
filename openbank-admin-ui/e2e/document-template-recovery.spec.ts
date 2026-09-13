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
  status: 'PUBLISHED',
  updatedAt: '2026-08-31T12:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('preserves the last template publication state through refresh outage and recovery', async ({ page }) => {
  let failNextRequest = false
  await page.route('**/api/svc/document-service/api/v1/documents/templates?limit=200', route => {
    if (failNextRequest) {
      failNextRequest = false
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"document service unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([TEMPLATE]) })
  })

  await page.goto('/document-templates')
  await expect(page.getByText(TEMPLATE.code, { exact: true })).toBeVisible()
  await expect(page.getByText('PUBLISHED', { exact: true })).toBeVisible()

  failNextRequest = true
  await page.getByRole('button', { name: /Obnovit šablony dokumentů|Refresh document templates/ }).click()

  const stale = page.getByRole('status').filter({ hasText: /poslední dostupná data|last available data/ })
  await expect(stale).toBeVisible()
  await expect(page.getByText(TEMPLATE.code, { exact: true })).toBeVisible()
  await expect(page.getByText('PUBLISHED', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: /Obnovit šablony dokumentů|Refresh document templates/ }).click()
  await expect(stale).toBeHidden()
  await expect(page.getByText(TEMPLATE.code, { exact: true })).toBeVisible()
})
