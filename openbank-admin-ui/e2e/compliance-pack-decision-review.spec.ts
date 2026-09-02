// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const proposal = {
  id: '019fb939-3e0a-7716-a1ed-7854754c8786',
  jurisdiction: 'CZ',
  productType: 'CONSUMER_CREDIT',
  packVersion: 3,
  effectiveFrom: '2026-10-01',
  contentHash: 'b7c4d1e9a0f35286bb1122334455667788990011223344556677889900aabbcc',
  state: 'PROPOSED',
  proposedBy: 'maker@openbank.test',
  decidedBy: null,
  decidedAt: null,
  proposedAt: '2026-08-31T20:00:00Z',
  decisionReason: null,
  pack: { jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', version: 3 },
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('checker reviews exact pack, retains a refusal, and retries the unchanged decision', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  await page.route('**/api/svc/lending-service/api/v1/lending/compliance-packs/active', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/svc/lending-service/api/v1/lending/compliance-packs/proposals/pending', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([proposal]) }),
  )

  let attempts = 0
  const payloads: unknown[] = []
  await page.route(`**/api/svc/lending-service/api/v1/lending/compliance-packs/proposals/${proposal.id}/decide`, async route => {
    attempts += 1
    payloads.push(route.request().postDataJSON())
    if (attempts === 1) {
      return route.fulfill({ status: 422, contentType: 'application/json', body: JSON.stringify({ error: 'Checker must differ from maker' }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
  })

  await page.goto('/lending/compliance-packs')
  await page.getByRole('textbox', { name: 'Decision reason' }).fill('independent legal review')
  await page.getByRole('button', { name: 'Approve' }).click()

  const dialog = page.getByRole('alertdialog', { name: 'Review pack activation' })
  await expect(dialog).toContainText('immediately governs new lending applications')
  await expect(dialog).toContainText(proposal.contentHash)
  await expect(dialog).toContainText(proposal.id)
  await expect(dialog).toContainText('independent legal review')
  expect(attempts).toBe(0)

  await dialog.getByRole('button', { name: 'Confirm activation' }).click()
  await expect(dialog.getByTestId('decision-review-error')).toContainText('Checker must differ from maker')
  await expect(dialog).toBeVisible()

  await dialog.getByRole('button', { name: 'Confirm activation' }).click()
  await expect(dialog).toBeHidden()
  expect(attempts).toBe(2)
  expect(payloads).toEqual([
    { approve: true, reason: 'independent legal review' },
    { approve: true, reason: 'independent legal review' },
  ])
})
