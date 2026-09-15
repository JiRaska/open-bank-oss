// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
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

test('explains a Czech catalogue outage and recovers on a narrow screen', async ({ page, baseURL }) => {
  await page.context().addCookies([{
    name: 'openbank-admin-lang',
    value: 'cs',
    url: baseURL!,
  }])
  await page.setViewportSize({ width: 320, height: 760 })
  let attempts = 0
  await page.route(/\/api\/audiences$/, route => {
    attempts += 1
    return route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify(attempts === 1
        ? { state: 'unreachable', items: [] }
        : { state: 'ok', items: [] }),
    })
  })

  await page.goto('/segments')
  await expect(page.getByText('Campaign-service neodpovídá')).toBeVisible()
  await page.getByRole('button', { name: 'Zkusit načíst znovu' }).click()
  await expect(page.getByText('Katalog je prázdný')).toBeVisible()
  await expect(page.getByText(/nezávisle schválit další člověk/)).toBeVisible()

  const width = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }))
  expect(width.scroll).toBeLessThanOrEqual(width.client)
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze()
  expect(results.violations).toEqual([])
  expect(attempts).toBe(2)
})
