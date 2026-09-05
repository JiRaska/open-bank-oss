// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const template = {
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

test.describe('document template lifecycle', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
  })

  test('requires explicit informed confirmation before publish and retire', async ({ page }) => {
    let status: 'DRAFT' | 'PUBLISHED' | 'RETIRED' = 'DRAFT'
    let publishRequests = 0
    let retireRequests = 0

    await page.route('**/api/svc/document-service/api/v1/documents/templates/*/*', async route => {
      const action = new URL(route.request().url()).pathname.split('/').at(-1)
      expect(route.request().method()).toBe('POST')
      if (action === 'publish') {
        publishRequests += 1
        expect(status).toBe('DRAFT')
        status = 'PUBLISHED'
      } else if (action === 'retire') {
        retireRequests += 1
        expect(status).toBe('PUBLISHED')
        status = 'RETIRED'
      } else {
        throw new Error(`Unexpected document-template action: ${action}`)
      }
      await route.fulfill({ status: 200, body: '' })
    })
    await page.route('**/api/svc/document-service/api/v1/documents/templates?limit=200', route =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify([{ ...template, status }]) }),
    )

    await page.goto('/document-templates')
    await expect(page.getByText(template.code, { exact: true })).toBeVisible()
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible()

    await page.getByRole('button', { name: /Publish template|Publikovat šablonu/ }).click()
    await expect(page.getByText(/A published version is immutable|Publikovaná verze je neměnná/)).toBeVisible()
    expect(publishRequests).toBe(0)
    await page.getByRole('button', { name: /Confirm|Potvrdit/ }).click()

    await expect(page.getByText('PUBLISHED', { exact: true })).toBeVisible()
    expect(publishRequests).toBe(1)

    await page.getByRole('button', { name: /Retire template|Vyřadit šablonu/ }).click()
    await expect(page.getByText(/stops being offered for new document generation|přestane nabízet pro generování nových dokumentů/)).toBeVisible()
    expect(retireRequests).toBe(0)
    await page.getByRole('button', { name: /Confirm|Potvrdit/ }).click()

    await expect(page.getByText('RETIRED', { exact: true })).toBeVisible()
    expect(retireRequests).toBe(1)
  })
})
