// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const previous = {
  id: 'close-run-2026-07',
  trigger: 'SCHEDULED',
  status: 'COMPLETED',
  periodFrom: '2026-07-01',
  periodTo: '2026-07-31',
  accountsEnumerated: 42,
  pocketsClosed: 48,
  pocketsFailed: 0,
  pocketsSkipped: 1,
  startedAt: '2026-08-01T00:30:00Z',
  finishedAt: '2026-08-01T00:31:00Z',
}
const accepted = {
  ...previous,
  id: 'close-run-2026-08',
  trigger: 'MANUAL',
  status: 'RUNNING',
  periodFrom: '2026-08-01',
  periodTo: '2026-08-31',
  accountsEnumerated: 0,
  pocketsClosed: 0,
  pocketsSkipped: 0,
  startedAt: '2026-09-01T00:00:00Z',
  finishedAt: null,
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('reviews the latest close evidence and retains a failed catch-up trigger for retry', async ({ page }) => {
  let attempts = 0
  let history: Array<typeof previous | typeof accepted> = [previous]
  await page.route(/\/api\/closings\/runs\?limit=/, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify(history),
  }))
  await page.route(/\/api\/closings\/runs$/, async route => {
    attempts += 1
    expect(route.request().method()).toBe('POST')
    expect(route.request().postData()).toBeNull()
    if (attempts === 1) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
      return
    }
    history = [accepted, previous]
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(accepted) })
  })

  await page.goto('/day-end?tab=eom')
  await page.getByRole('button', { name: /Review catch-up close|Zkontrolovat catch-up uzávěrku/ }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('close-run-2026-07 · COMPLETED · SCHEDULED')
  await expect(dialog).toContainText(/1st of month · 02:30|1\. den v měsíci · 02:30/)
  await expect(dialog).toContainText(/Acceptance only confirms the start|Přijetí pouze potvrzuje zahájení/)

  const confirm = dialog.getByRole('button', { name: /Confirm run|Potvrdit spuštění/ })
  await confirm.click()
  await expect(dialog.getByRole('alert')).toContainText(/Could not start the catch-up close run|Spuštění catch-up uzávěrky se nezdařilo/)
  await expect(dialog).toBeVisible()
  await confirm.click()

  await expect(dialog).toBeHidden()
  await expect(page.getByText(/Catch-up close run accepted|Catch-up uzávěrka přijata/)).toBeVisible()
  await expect(page.getByText(/Running|Běží/).first()).toBeVisible()
  await expect(page.getByText(/Manual|Ruční/).first()).toBeVisible()
  expect(attempts).toBe(2)
})
