// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const NOTIFICATION = {
  id: 'notification-42',
  type: 'EMAIL',
  channel: 'EMAIL',
  recipient: 'customer-42@example.test',
  subject: 'Payment received',
  status: 'SENT',
  sentAt: '2026-08-31T08:01:00Z',
  createdAt: '2026-08-31T08:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last notification log during an outage and recovers', async ({ page }) => {
  let available = true
  let requests = 0
  await page.route('**/api/svc/notification-service/api/v1/notifications', route => {
    requests += 1
    if (!available) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify([NOTIFICATION]) })
  })

  await page.goto('/notifications')
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toBeVisible()

  available = false
  await page.getByRole('button', { name: /Obnovit oznámení|Refresh notifications/ }).click()

  await expect(page.getByText(/stav doručení se mohl změnit|delivery status may have changed/)).toBeVisible()
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toBeVisible()
  await expect(page.getByText(/Žádné notifikace nenalezeny|No notifications found/)).toHaveCount(0)

  available = true
  await page.getByRole('button', { name: /Obnovit oznámení|Refresh notifications/ }).click()
  await expect(page.getByText(/stav doručení se mohl změnit|delivery status may have changed/)).toBeHidden()
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toBeVisible()
  expect(requests).toBeGreaterThanOrEqual(3)
})
