// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator, signInWithRoles } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/entities/resolve?**', route => route.fulfill({ status: 503, body: '{}' }))
  await page.route('**/api/services/governance', route => route.fulfill({ json: { items: [] } }))
  await page.route('**/api/services/health', route => route.fulfill({ json: { source: 'kubernetes', discoveryStatus: 'complete', services: [] } }))
})

test('finds menu destinations immediately during a record-search outage and navigates by keyboard', async ({ page }) => {
  await page.goto('/accounts')
  await page.getByRole('button', { name: /Quick search|Rychlé hledání/ }).click()
  const dialog = page.getByRole('dialog', { name: /Quick search|Rychlé hledání/ })
  await dialog.getByRole('textbox').fill('transakce')
  const result = dialog.getByRole('option', { name: /Transactions|Transakce/ }).first()
  await expect(result).toBeVisible()
  await expect(result).toHaveAttribute('aria-selected', 'true')
  await expect(dialog.getByRole('alert')).toBeVisible()
  await expect(result).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/transactions$/)
  await expect(page.locator('#admin-sidebar a[aria-current="page"]')).toHaveCount(1)
})

test('switches areas, saves preferences and restores a named filtered view after reload', async ({ page }, testInfo) => {
  await page.goto('/accounts')
  const sidebar = page.locator('#admin-sidebar')
  await sidebar.getByRole('button', { name: /Workspace area|Pracovní oblast/ }).click()
  await page.locator('#navigation-areas').getByRole('button', { name: /Documentation|Dokumentace/ }).click()
  await expect(sidebar.getByRole('link', { name: /Service Docs|Dokumentace služeb/ })).toBeVisible()
  await sidebar.getByRole('button', { name: /Compact menu|Kompaktní menu/ }).click()
  await sidebar.getByRole('slider', { name: /Navigation width|Šířka navigace/ }).fill('320')
  await page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/).selectOption('ACTIVE')
  await page.getByRole('button', { name: /Pin or unpin current view|Připnout nebo odepnout/ }).click()
  const pinDialog = page.getByRole('dialog', { name: /Pin view|Připnout pohled/ })
  await pinDialog.getByRole('textbox').fill('Active account workspace')
  await pinDialog.getByRole('button', { name: /^(Pin|Připnout)$/ }).click()
  await expect(sidebar.getByRole('link', { name: 'Active account workspace' })).toBeVisible()
  await page.reload()
  await expect(page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/)).toHaveValue('ACTIVE')
  await expect(sidebar.getByRole('button', { name: /Compact menu|Kompaktní menu/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(sidebar.getByRole('slider')).toHaveValue('320')
  await expect(sidebar.getByRole('link', { name: 'Active account workspace' })).toBeVisible()
  await page.getByRole('button', { name: /Quick search|Rychlé hledání/ }).click()
  const search = page.getByRole('dialog', { name: /Quick search|Rychlé hledání/ })
  await expect(search.getByRole('option', { name: /Active account workspace/ })).toBeVisible()
  await page.keyboard.press('Escape')
  const a11y = await new AxeBuilder({ page }).include('#admin-sidebar').analyze()
  expect(a11y.violations).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('navigation-workspace.png'), fullPage: true })
})

test('hands an explicit question to the assistant with visible context and requires sending', async ({ page }) => {
  let posts = 0
  await page.route('**/api/agent/chat', async route => {
    if (route.request().method() === 'GET') return route.fulfill({ json: { default: '', models: [] } })
    posts += 1
    const body = route.request().postDataJSON()
    expect(body.context).toBe('admin page /accounts')
    expect(body.messages.at(-1).content).toBe('Explain account states')
    await route.fulfill({ json: { reply: 'Account states describe the lifecycle.', toolCalls: [] } })
  })
  await page.goto('/accounts')
  await page.getByRole('button', { name: /Quick search|Rychlé hledání/ }).click()
  const dialog = page.getByRole('dialog', { name: /Quick search|Rychlé hledání/ })
  await dialog.getByRole('textbox').fill('Explain account states')
  await dialog.getByRole('button', { name: /Ask the assistant|Zeptat se asistenta/ }).click()
  const assistant = page.getByRole('region', { name: /assistant panel|Panel asistenta/ })
  await expect(assistant).toBeVisible()
  await expect(assistant.getByRole('textbox')).toHaveValue('Explain account states')
  await expect(assistant.getByRole('textbox')).toBeFocused()
  await expect(assistant).toContainText(/Context: Accounts|Kontext: Účty/)
  expect(posts).toBe(0)
  await assistant.getByRole('button', { name: /Send message|Odeslat zprávu/ }).click()
  await expect(assistant).toContainText('Account states describe the lifecycle.')
  expect(posts).toBe(1)
})

test('keeps inaccessible destinations out of search for a restricted persona', async ({ context, baseURL, page }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_VIEWER'])
  await page.goto('/accounts')
  await page.getByRole('button', { name: /Quick search|Rychlé hledání/ }).click()
  const dialog = page.getByRole('dialog', { name: /Quick search|Rychlé hledání/ })
  await dialog.getByRole('button', { name: /^(Pages|Stránky)$/ }).click()
  await dialog.getByRole('textbox').fill('configuration')
  await expect(dialog.getByRole('option')).toHaveCount(0)
  await expect(dialog.getByText(/No results|Nic nenalezeno/)).toBeVisible()
})

test('switches between saved filters on the same route and clears them on a plain-page link', async ({ page }) => {
  await page.goto('/accounts')
  const sidebar = page.locator('#admin-sidebar')
  for (const status of ['ACTIVE', 'CLOSED']) {
    await page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/).selectOption(status)
    await page.getByRole('button', { name: /Pin or unpin current view|Připnout nebo odepnout/ }).click()
    const dialog = page.getByRole('dialog', { name: /Pin view|Připnout pohled/ })
    await dialog.getByRole('textbox').fill(`Accounts ${status}`)
    await dialog.getByRole('button', { name: /^(Pin|Připnout)$/ }).click()
  }
  await sidebar.getByRole('link', { name: 'Accounts ACTIVE', exact: true }).click()
  await expect(page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/)).toHaveValue('ACTIVE')
  await sidebar.getByRole('link', { name: 'Accounts CLOSED', exact: true }).click()
  await expect(page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/)).toHaveValue('CLOSED')
  await sidebar.locator('a[href="/accounts"]').first().click()
  await expect(page.getByLabel(/Filter by account status|Filtrovat podle stavu účtu/)).toHaveValue('')
})

test('uses a selected record as assistant context without navigating away', async ({ page }, testInfo) => {
  await page.route('**/api/entities/resolve?**', route => route.fulfill({ json: { results: [
    { type: 'party', id: 'party-fixture', label: 'Example Company', route: '/parties/party-fixture' },
  ] } }))
  await page.route('**/api/agent/chat', route => route.fulfill({ json: { default: '', models: [] } }))
  await page.goto('/accounts')
  await page.getByRole('button', { name: /Quick search|Rychlé hledání/ }).click()
  const dialog = page.getByRole('dialog', { name: /Quick search|Rychlé hledání/ })
  await dialog.getByRole('textbox').fill('example')
  await expect(dialog.getByRole('option', { name: /Example Company/ })).toBeVisible()
  await expect(dialog.getByRole('option', { name: /Example Company/ })).toHaveAttribute('aria-selected', 'true')
  const a11y = await new AxeBuilder({ page }).include('[role="dialog"]').analyze()
  expect(a11y.violations).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('navigation-search.png') })
  await dialog.getByRole('button', { name: /Ask about result|Zeptat se k výsledku/ }).click()
  await expect(page.getByRole('region', { name: /assistant panel|Panel asistenta/ })).toContainText('Example Company')
  await expect(page).toHaveURL(/\/accounts$/)
})
