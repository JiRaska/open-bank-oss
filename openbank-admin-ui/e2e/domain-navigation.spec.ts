// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test'
import { waitForSettledShell } from './helpers/shell'
import { signInAsOperator } from './helpers/auth'

test('server-rendered navigation follows reviewed business ownership without changing destinations', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  const catalog = await (await page.request.get('/api/catalog/services')).json()
  expect(catalog.services.find((row: { name: string }) => row.name === 'openbank-ledger-service').dataDomain).toBe('accounting')
  await page.goto('/docs/service-map')
  await waitForSettledShell(page)
  const sidebar = page.getByRole('complementary', { name: 'Main navigation' })
  for (const [domain, href] of [
    ['Financial accounting', '/ledger'], ['Financial accounting', '/transactions'],
    ['Products & pricing', '/product-catalog'], ['Products & pricing', '/fees'],
    ['Cards', '/cards'], ['Cards', '/disputes'], ['Pensions', '/pension/funds'],
    ['Treasury', '/treasury/deals'], ['Lending', '/lending/risk'],
    ['Risk management', '/balance-sheet/snapshots'], ['Open banking', '/consents'],
    ['Customer engagement', '/campaigns'], ['Document services', '/document-templates'],
  ]) {
    await sidebar.getByRole('button', { name: /Workspace area/ }).click()
    await page.locator('#navigation-areas').getByRole('button', { name: domain, exact: true }).click()
    await expect(sidebar.getByRole('region', { name: domain, exact: true }).locator(`a[href="${href}"]`)).toHaveCount(1)
  }
  await sidebar.getByRole('button', { name: /Workspace area/ }).click()
  await page.locator('#navigation-areas').getByRole('button', { name: 'Payments', exact: true }).click()
  await expect(sidebar.getByRole('region', { name: 'Payments', exact: true }).locator('a[href="/product-catalog"]')).toHaveCount(0)
  await page.getByRole('button', { name: /Switch to Czech/ }).click()
  const czechSidebar = page.getByRole('complementary', { name: 'Hlavní navigace' })
  await czechSidebar.getByRole('button', { name: /Pracovní oblast/ }).click()
  await page.locator('#navigation-areas').getByRole('button', { name: 'Finanční účetnictví', exact: true }).click()
  await expect(czechSidebar.getByRole('region', { name: 'Finanční účetnictví', exact: true }).locator('a[href="/ledger"]')).toHaveCount(1)
  await czechSidebar.getByRole('button', { name: /Pracovní oblast/ }).click()
  await page.locator('#navigation-areas').getByRole('button', { name: 'Penzijní produkty a fondy', exact: true }).click()
  await expect(czechSidebar.getByRole('region', { name: 'Penzijní produkty a fondy', exact: true }).locator('a[href="/pension/funds"]')).toHaveCount(1)
})
