// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { expect, test } from '@playwright/test'
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
