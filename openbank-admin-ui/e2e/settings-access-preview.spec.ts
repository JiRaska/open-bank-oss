// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { waitForSettledShell } from './helpers/shell'
import { signInAsOperator } from './helpers/auth'
import { PERMISSIONS, ROLES } from '../src/lib/auth/roles'

test('settings exposes an incomplete read-only projection of every UI grant', async ({ page, context, baseURL }, testInfo) => {
  test.setTimeout(90_000)
  await signInAsOperator(context, baseURL!)
  await page.goto('/settings')
  await waitForSettledShell(page)
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
    await waitForSettledShell(page)
    const languageButton = page.getByRole('button', { name: language === 'cs' ? /EN: Switch to Czech/ : /CS: Přepnout na angličtinu/ })
    if (await languageButton.count()) await languageButton.click()
    await expect(page.locator('html')).toHaveAttribute('lang', language)
    const preview = page.getByRole('region', { name: /UI access rule matrix|Matice přístupových pravidel UI/ })
    await page.getByLabel(/Find permission|Hledat oprávnění/).fill('Approve payment')
    await page.getByRole('combobox', { name: /^(Role)$/ }).selectOption('ROLE_ADMIN')
    await expect(preview.getByRole('columnheader').last()).toContainText(language === 'cs' ? 'Administrátor' : 'Administrator')
    await page.getByRole('checkbox', { name: /Show descriptions|Zobrazit popisy/ }).check()
    await expect(preview.getByRole('rowheader')).toContainText(language === 'cs' ? 'Schválení platby' : 'Approve payment')
    await expect(preview.getByRole('rowheader')).toContainText(language === 'cs' ? 'Rozhodnutí o platbě čekající na schválení.' : 'Decide on a payment awaiting approval.')
    await expect(preview.getByRole('cell')).toHaveText(['✓'])
  })
}

test('matrix compares a custom role selection and keeps labels pinned while scrolling', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.goto('/settings')
  await waitForSettledShell(page)
  const matrix = page.getByRole('region', { name: /UI access rule matrix|Matice přístupových pravidel UI/ })
  await expect(matrix).toBeVisible()
  await page.getByText(/Choose roles to compare|Vybrat role k porovnání/).click()
  await page.getByRole('button', { name: /Clear role selection|Zrušit výběr rolí/ }).click()
  await expect(matrix.getByText(/Select at least one role|Vyberte alespoň jednu roli/)).toBeVisible()
  await page.getByRole('checkbox', { name: /ROLE_ADMIN$/ }).check()
  await page.getByRole('checkbox', { name: /ROLE_VIEWER$/ }).check()
  await expect(matrix.getByRole('columnheader')).toHaveCount(3)
  await page.getByLabel(/Find permission|Hledat oprávnění/).fill('payments:approve')
  await expect(matrix.getByRole('cell')).toHaveText(['✓', '—'])
  await page.getByRole('checkbox', { name: /ROLE_ADMIN$/ }).uncheck()
  await page.getByRole('checkbox', { name: /Only grants listed|Jen oprávnění uvedená/ }).check()
  await expect(matrix.getByText(/No matching permissions|Žádné odpovídající oprávnění/)).toBeVisible()
  await page.getByRole('button', { name: /Select all roles|Vybrat všechny role/ }).click()
  await page.getByRole('checkbox', { name: /Only grants listed|Jen oprávnění uvedená/ }).uncheck()
  await page.getByLabel(/Find permission|Hledat oprávnění/).fill('')
  const before = await matrix.evaluate(el => {
    const first = el.querySelector('tbody th')!.getBoundingClientRect()
    const header = el.querySelector('thead th')!.getBoundingClientRect()
    el.scrollLeft = 600
    el.scrollTop = 300
    return { left: first.left, top: header.top }
  })
  const after = await matrix.evaluate(el => ({ left: el.querySelector('tbody th')!.getBoundingClientRect().left, top: el.querySelector('thead th')!.getBoundingClientRect().top }))
  expect(after.left).toBeCloseTo(before.left, 0)
  expect(after.top).toBeCloseTo(before.top, 0)
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
