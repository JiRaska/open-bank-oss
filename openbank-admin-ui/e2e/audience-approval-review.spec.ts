// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const audience = {
  name: 'actives-tenured-30d',
  version: 4,
  rules: ['party status is ACTIVE', 'tenure >= 30 days'],
  state: 'PENDING_APPROVAL',
  createdBy: 'maker.operator',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('reviews exact audience evidence and retains a failed approval for retry', async ({ page }) => {
  let decisions = 0
  let items = [audience]
  await page.route(/\/api\/audiences$/, route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items }),
  }))
  await page.route(`**/api/audiences/${audience.name}/${audience.version}/approve`, async route => {
    decisions += 1
    expect(route.request().method()).toBe('POST')
    if (decisions === 1) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
      return
    }
    items = []
    await route.fulfill({ contentType: 'application/json', body: '{}' })
  })

  await page.goto('/segments')
  await page.getByRole('button', { name: /Review and approve|Zkontrolovat a schválit/ }).click()
  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText(`${audience.name} · v${audience.version}`)
  await expect(dialog).toContainText(audience.createdBy)
  await expect(dialog).toContainText(audience.rules[0])
  await expect(dialog).toContainText(audience.rules[1])

  const confirm = dialog.getByRole('button', { name: /Confirm approval|Potvrdit schválení/ })
  await confirm.click()
  await expect(dialog.getByRole('alert')).toContainText(/state change did not complete|Změna stavu publika se nepodařila/)
  await expect(dialog).toBeVisible()
  await confirm.click()

  await expect(dialog).toBeHidden()
  await expect(page.getByText(/catalogue is empty|Katalog je prázdný/i)).toBeVisible()
  expect(decisions).toBe(2)
})
