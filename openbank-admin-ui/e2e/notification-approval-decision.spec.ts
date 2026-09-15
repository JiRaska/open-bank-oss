// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const notificationsPath = '/api/svc/notification-service/api/v1/notifications'
const approvalPath = `${notificationsPath}/approvals/approval-42`

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route(url => url.pathname === notificationsPath, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ items: [], total: 0, page: 0, size: 20 }),
  }))
})

test('reviews the exact notification approval before one privileged write', async ({ page }) => {
  let decisions = 0
  await page.route(url => url.pathname === approvalPath, async route => {
    decisions += 1
    expect(route.request().method()).toBe('PATCH')
    expect(route.request().postDataJSON()).toEqual({ approve: true })
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ id: 'approval-42', status: 'APPROVED' }),
    })
  })

  await page.goto('/notifications?approvalId=approval-42#notification-approval-id')
  const approvalId = page.getByRole('textbox', { name: /ID schválení notifikace|Notification approval ID/ })
  await expect(approvalId).toHaveValue('approval-42')

  await page.getByRole('button', { name: /Schválit|Approve/, exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('approval-42')
  await expect(dialog).toContainText(/odmítne schválení vlastní zprávy|refuses self-approval/)
  expect(decisions).toBe(0)

  await dialog.getByRole('button', { name: /Potvrdit schválení|Confirm approval/ }).click()
  await expect(page.getByRole('status')).toContainText(/Rozhodnutí uloženo: APPROVED|Decision recorded: APPROVED/)
  expect(decisions).toBe(1)
  await expect(approvalId).toHaveValue('')
  await expect(approvalId).toBeFocused()
})

test('keeps a rejected decision bound to its id for a safe retry', async ({ page }) => {
  let attempts = 0
  await page.route(url => url.pathname === approvalPath, async route => {
    attempts += 1
    expect(route.request().method()).toBe('PATCH')
    expect(route.request().postDataJSON()).toEqual({ approve: false })
    if (attempts === 1) {
      await route.fulfill({ status: 409, contentType: 'application/json', body: '{"message":"already deciding"}' })
      return
    }
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ id: 'approval-42', status: 'REJECTED' }),
    })
  })

  await page.goto('/notifications?approvalId=approval-42#notification-approval-id')
  await page.getByRole('button', { name: /Zamítnout|Reject/, exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  const confirm = dialog.getByRole('button', { name: /Potvrdit zamítnutí|Confirm rejection/ })
  await confirm.click()

  await expect(dialog.getByRole('alert')).toContainText(/Rozhodnutí se nezdařilo|The decision failed/)
  await expect(dialog).toContainText('approval-42')
  await confirm.click()

  await expect(page.getByRole('status')).toContainText(/Rozhodnutí uloženo: REJECTED|Decision recorded: REJECTED/)
  expect(attempts).toBe(2)
})
