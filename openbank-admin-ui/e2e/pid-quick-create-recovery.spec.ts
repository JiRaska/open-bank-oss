// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY_ID = '77777777-7777-4777-8777-777777777777'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('resumes BankID sync without creating the party again', async ({ page }) => {
  let createAttempts = 0
  let syncAttempts = 0

  await page.route('**/api/svc/pid-service/api/v1/pids', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/svc/pid-service/api/v1/parties', route => {
    createAttempts += 1
    return route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ id: PARTY_ID }),
    })
  })
  await page.route(`**/api/svc/pid-service/api/v1/parties/${PARTY_ID}/sync/bankid`, route => {
    syncAttempts += 1
    return route.fulfill({
      status: syncAttempts === 1 ? 503 : 200,
      contentType: 'application/json',
      body: JSON.stringify(syncAttempts === 1 ? { code: 'UPSTREAM_UNAVAILABLE' } : { id: PARTY_ID }),
    })
  })

  await page.goto('/pid')
  await page.getByRole('button', { name: 'Open PID quick create' }).click()
  await page.locator('#pid-given-name').fill('Ada')
  await page.locator('#pid-family-name').fill('Lovelace')
  await page.locator('#pid-birthdate').fill('1985-04-12')
  await page.locator('#pid-gender').selectOption('FEMALE')
  await page.locator('#pid-birthplace').fill('Praha')
  await page.locator('#pid-bankid-sub').fill('bankid|recoverable-subject')
  await page.locator('#pid-document-number').fill('ID-123456')
  await page.locator('#pid-document-issued-at').fill('2024-01-02')
  await page.locator('#pid-document-expires-at').fill('2034-01-02')

  await page.getByRole('button', { name: 'Create', exact: true }).click()
  await expect(page.getByText(`Record created (ID: ${PARTY_ID})`, { exact: false })).toBeVisible()
  await expect(page.locator('#pid-bankid-sub')).toBeDisabled()

  await page.getByRole('button', { name: 'Retry BankID sync' }).click()
  await expect(page.getByText('Record created and synchronized successfully.')).toBeVisible()
  expect(createAttempts).toBe(1)
  expect(syncAttempts).toBe(2)
})
