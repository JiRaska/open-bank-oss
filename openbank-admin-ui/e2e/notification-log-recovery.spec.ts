// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const NOTIFICATION = {
  id: 'notification-42',
  template: 'PAYMENT_COMPLETED',
  channel: 'EMAIL',
  recipient: 'customer-42@example.test',
  subject: 'Payment received',
  status: 'SENT',
  sentAt: '2026-08-31T08:01:00Z',
  createdAt: '2026-08-31T08:00:00Z',
}

const RECOVERED_NOTIFICATION = {
  ...NOTIFICATION,
  recipient: 'customer-42-recovered@example.test',
  subject: 'Payment notification recovered',
  sentAt: '2026-08-31T08:02:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the last notification log during an outage and recovers', async ({ page }) => {
  let available = true
  let currentNotification = NOTIFICATION
  let requests = 0
  const requestedPages: string[] = []
  await page.route(url => url.pathname === '/api/svc/notification-service/api/v1/notifications', route => {
    requests += 1
    const requestUrl = new URL(route.request().url())
    requestedPages.push(`${requestUrl.searchParams.get('page')}:${requestUrl.searchParams.get('size')}`)
    if (!available) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"unavailable"}' })
    }
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ items: [currentNotification], total: 1, page: 0, size: 20 }),
    })
  })

  await page.goto('/notifications')
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toBeVisible()

  available = false
  await page.getByRole('button', { name: /Obnovit oznámení|Refresh notifications/ }).click()

  await expect(page.getByText(/stav doručení se mohl změnit|delivery status may have changed/)).toBeVisible()
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toBeVisible()
  await expect(page.getByText(/Žádné notifikace nenalezeny|No notifications found/)).toHaveCount(0)

  available = true
  currentNotification = RECOVERED_NOTIFICATION
  await page.getByRole('button', { name: /Obnovit oznámení|Refresh notifications/ }).click()
  await expect(page.getByText(/stav doručení se mohl změnit|delivery status may have changed/)).toBeHidden()
  await expect(page.getByText(RECOVERED_NOTIFICATION.recipient, { exact: true })).toBeVisible()
  await expect(page.getByText(NOTIFICATION.recipient, { exact: true })).toHaveCount(0)
  expect(requests).toBeGreaterThanOrEqual(3)
  expect(requestedPages).toEqual(Array(requests).fill('0:20'))
})
