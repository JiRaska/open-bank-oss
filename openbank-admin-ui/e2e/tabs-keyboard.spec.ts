// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps horizontal document tabs operable and panel-linked from the keyboard', async ({ page }) => {
  await page.route('**/api/svc/document-service/api/v1/documents/templates?limit=200', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.goto('/document-templates')

  const tablist = page.getByRole('tablist', { name: /Document template content|Obsah šablon dokumentů/ })
  const templates = tablist.getByRole('tab', { name: /Templates|Šablony/ })
  const documents = tablist.getByRole('tab', { name: /Documents|Dokumenty/ })
  await expect(templates).toHaveAttribute('aria-selected', 'true')
  await templates.focus()
  await page.keyboard.press('ArrowRight')

  await expect(documents).toBeFocused()
  await expect(documents).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#document-documents-panel')).toBeVisible()
})

test('keeps vertical settings tabs wrapping and automatically activated', async ({ page }) => {
  await page.goto('/settings')

  const tablist = page.getByRole('tablist', { name: /Settings sections|Sekce nastavení/ })
  const profile = tablist.getByRole('tab', { name: /Profile|Profil/ })
  const language = tablist.getByRole('tab', { name: /Language|Jazyk/ })
  await profile.focus()
  await page.keyboard.press('ArrowUp')

  await expect(language).toBeFocused()
  await expect(language).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#settings-panel-regional')).toBeVisible()
})

test('selected settings tab remains readable in both verified themes', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/settings', { waitUntil: 'domcontentloaded' })
  const tabs = page.getByRole('tablist', { name: /Settings sections|Sekce nastavení/ })
  const selected = tabs.getByRole('tab', { selected: true })
  await expect(selected).toBeVisible()

  for (const dark of [false, true]) {
    const colours = await page.evaluate(enabled => {
      document.documentElement.classList.toggle('dark', enabled)
      const root = getComputedStyle(document.documentElement)
      return {
        text: root.getPropertyValue('--accent-text').trim(),
        surface: root.getPropertyValue('--accent-light').trim(),
      }
    }, dark)
    expect(colours.text).toBe(dark ? '#c7d2fe' : '#4338ca')
    expect(colours.surface).toBe(dark ? '#1e1b4b' : '#eef2ff')

    const scan = await new AxeBuilder({ page }).include('[role="tablist"]').withTags(['wcag2aa', 'wcag21aa']).analyze()
    expect(scan.violations, JSON.stringify(scan.violations)).toEqual([])
    await expect(selected).toHaveCSS('color', dark ? 'rgb(199, 210, 254)' : 'rgb(67, 56, 202)')
  }
})
