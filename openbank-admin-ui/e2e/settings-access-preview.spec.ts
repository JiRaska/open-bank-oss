// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { PERMISSIONS, ROLES } from '../src/lib/auth/roles'

test('settings exposes an incomplete read-only projection of every UI grant', async ({ page, context, baseURL }, testInfo) => {
  test.setTimeout(90_000)
  await signInAsOperator(context, baseURL!)
  await page.goto('/settings')
  const preview = page.getByRole('region', { name: /UI access rule matrix|Matice přístupových pravidel UI/ })
  await expect(preview).toBeVisible()
  await expect(preview.getByRole('columnheader')).toHaveCount(Object.values(ROLES).length + 1)
  await expect(preview.getByRole('row')).toHaveCount(Object.keys(PERMISSIONS).length + 1)
  for (const [permission, grants] of Object.entries(PERMISSIONS)) {
    const row = preview.getByRole('row').filter({ has: page.getByRole('rowheader').filter({ has: page.getByText(permission, { exact: true }) }) })
    await expect(row.getByRole('cell')).toHaveText(Object.values(ROLES).map(role => (grants as readonly string[]).includes(role) ? '✓' : '—'))
  }
  const accessibility = await new AxeBuilder({ page }).include('section[aria-labelledby="access-preview-title"]').analyze()
  expect(accessibility.violations).toEqual([])
  await preview.screenshot({ path: testInfo.outputPath('access-preview-desktop.png') })
  await expect(preview.getByRole('button')).toHaveCount(0)
  await expect(preview.getByRole('checkbox')).toHaveCount(0)
  await page.getByLabel(/Find permission|Hledat oprávnění/).fill('schvaleni platby')
  await expect(preview.getByRole('row')).toHaveCount(2)
  const section = page.locator('section').filter({ has: preview })
  await section.getByRole('combobox').selectOption('ROLE_TREASURY_DEALER')
  await expect(preview.getByRole('columnheader')).toHaveCount(2)
  await expect(preview.getByRole('cell')).toHaveText(['—'])
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(preview).toBeVisible()
  await page.getByLabel(/Find permission|Hledat oprávnění/).fill('no-such-permission')
  await expect(preview.getByText(/No matching permissions|Žádné odpovídající oprávnění/)).toBeVisible()
})

for (const language of ['cs', 'en'] as const) {
  test(`access preview explains roles and permissions in ${language}`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await page.goto('/settings')
    const languageButton = page.getByRole('button', { name: language === 'cs' ? /EN: Switch to Czech/ : /CS: Přepnout na angličtinu/ })
    if (await languageButton.count()) await languageButton.click()
    const preview = page.getByRole('region', { name: /UI access rule matrix|Matice přístupových pravidel UI/ })
    await page.getByLabel(/Find permission|Hledat oprávnění/).fill('Approve payment')
    await page.getByRole('combobox', { name: /^(Role)$/ }).selectOption('ROLE_ADMIN')
    await expect(preview.getByRole('columnheader').last()).toContainText(language === 'cs' ? 'Administrátor' : 'Administrator')
    await expect(preview.getByRole('rowheader')).toContainText(language === 'cs' ? 'Schválení platby' : 'Approve payment')
    await expect(preview.getByRole('rowheader')).toContainText(language === 'cs' ? 'Rozhodnutí o platbě čekající na schválení.' : 'Decide on a payment awaiting approval.')
    await expect(preview.getByRole('cell')).toHaveText(['✓'])
  })
}
