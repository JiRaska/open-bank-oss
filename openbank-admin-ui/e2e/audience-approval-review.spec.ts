// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme, type OperatorTheme } from './helpers/theme'

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

for (const theme of ['light', 'dark'] as const satisfies readonly OperatorTheme[]) {
  test(`inherits the ${theme} theme across the audience card and approval evidence`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.route(/\/api\/audiences$/, route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ state: 'ok', items: [audience] }),
    }))

    await page.goto('/segments')
    const semanticSurface = await page.evaluate(() => {
      const probe = document.createElement('div')
      probe.style.backgroundColor = 'var(--surface)'
      document.body.append(probe)
      const color = getComputedStyle(probe).backgroundColor
      probe.remove()
      return color
    })
    const card = page.locator('[data-audience-card]').first()
    await expect(card).toBeVisible()
    await expect(card).toHaveCSS('background-color', semanticSurface)

    await page.getByRole('button', { name: /Review and approve|Zkontrolovat a schválit/ }).click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog).toBeVisible()
    const panel = dialog.locator('> div')
    await expect(panel).toHaveCSS('background-color', semanticSurface)

    const results = await new AxeBuilder({ page })
      .include('[role="alertdialog"]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })

  test(`validates the audience composer in the ${theme} theme`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.goto('/segments/new')
    const form = page.locator('[data-audience-create-form]')
    await expect(form).toBeVisible()

    await page.getByLabel(/Name|Název/).fill('Not valid')
    await page.getByLabel(/Name|Název/).blur()
    await expect(page.locator('#segment-name-error')).not.toBeEmpty()
    await page.getByLabel(/Minimum relationship age|Minimální délka vztahu/).fill('-1')
    await page.getByLabel(/Minimum relationship age|Minimální délka vztahu/).blur()
    await expect(page.locator('#segment-tenure-error')).not.toBeEmpty()

    const results = await new AxeBuilder({ page })
      .include('[data-audience-create-form]')
      .include('[data-audience-create-guidance]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
