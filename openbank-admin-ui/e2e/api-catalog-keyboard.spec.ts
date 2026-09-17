// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ byContainer: {}, source: 'test' }),
  }))
  await page.route('**/api/catalog/services', route => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: '{"error":"temporarily unavailable"}',
  }))
  await page.route('**/api/catalog/openapi/**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      openapi: '3.1.0',
      info: { title: 'Keyboard test API', version: '1.0.0' },
      paths: { '/api/v1/example': Object.fromEntries(
        ['get', 'post', 'put', 'patch', 'delete'].map(method => [method, {
          summary: `${method.toUpperCase()} example`,
          responses: {
            200: { description: 'OK' },
            400: { description: 'Bad request' },
            500: { description: 'Server error' },
          },
        }]),
      ) },
    }),
  }))
  await page.route('**/api/svc/**', route => route.fulfill({ status: 503, body: '{}' }))
})

test('unavailable fleet health is not reported as not deployed', async ({ page }) => {
  await page.route('**/api/services/health', route => route.fulfill({ status: 503, body: '{}' }))
  await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
  const account = page.locator('[role="button"][aria-controls="api-service-account"]')
  await expect(account.locator('.animate-spin')).toHaveCount(0)
  await expect(account).toContainText('Status unknown')
  await expect(account).not.toContainText('Not deployed')
  await expect(account).toContainText('1 endpoint')
})

test('unreported service health is not reported as down', async ({ page }) => {
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ byContainer: { 'account-service': { status: 'UNKNOWN' } }, source: 'test' }),
  }))
  await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
  const account = page.locator('[role="button"][aria-controls="api-service-account"]')
  await expect(account.locator('.animate-spin')).toHaveCount(0)
  await expect(account).toContainText('Status unknown')
  await expect(account).not.toContainText('Offline')
})

test('a service absent from a valid health snapshot remains not deployed', async ({ page }) => {
  await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
  const account = page.locator('[role="button"][aria-controls="api-service-account"]')
  await expect(account.locator('.animate-spin')).toHaveCount(0)
  await expect(account).toContainText('Not deployed')
  await expect(account).not.toContainText('Status unknown')
})

test('an explicit DOWN health verdict remains offline', async ({ page }) => {
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ byContainer: { 'account-service': { status: 'DOWN' } }, source: 'test' }),
  }))
  await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
  const account = page.locator('[role="button"][aria-controls="api-service-account"]')
  await expect(account.locator('.animate-spin')).toHaveCount(0)
  await expect(account.getByLabel('Offline')).toBeVisible()
  await expect(account).not.toContainText('Status unknown')
})

test('malformed health snapshot cannot silently erase every status', async ({ page }) => {
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ services: [] }),
  }))
  await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
  const account = page.locator('[role="button"][aria-controls="api-service-account"]')
  await expect(account.locator('.animate-spin')).toHaveCount(0)
  await expect(account).toContainText('Status unknown')
  await expect(account).toContainText('1 endpoint')
})

test('service disclosures work from the keyboard', async ({ page }) => {
  await page.goto('/docs/api')

  const service = page.locator('[role="button"][aria-controls^="api-service-"]').first()
  await expect(service).toHaveAttribute('aria-expanded', 'false')
  await expect(service.locator('.animate-spin')).toHaveCount(0)
  await service.focus()
  await page.keyboard.press('Enter')
  await expect(service).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Space')
  await expect(service).toHaveAttribute('aria-expanded', 'false')
})

test('nested service links keep native keyboard activation', async ({ page }) => {
  await page.goto('/docs/api')

  const service = page.locator('[role="button"][aria-controls^="api-service-"]').first()
  await expect(service).toHaveAttribute('aria-expanded', 'false')
  await expect(service.locator('.animate-spin')).toHaveCount(0)

  const changelog = service.getByRole('link', { name: 'Changelog' })
  const href = await changelog.getAttribute('href')
  expect(href).toBeTruthy()
  await changelog.focus()
  await Promise.all([
    page.waitForURL(url => url.pathname === href, { waitUntil: 'commit', timeout: 15_000 }),
    page.keyboard.press('Enter'),
  ])
})

for (const width of [1280, 320] as const) {
  for (const theme of ['light', 'dark'] as const) {
    test(`loaded API operations remain readable in ${theme} theme at ${width}px`, async ({ page, context, baseURL }) => {
      await context.addCookies([{ name: 'ob-admin-theme', value: theme, url: baseURL! }])
      await page.setViewportSize({ width, height: 900 })
      await page.route('**/api/services/health', route => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ byContainer: { 'account-service': { status: 'UP' } }, source: 'test' }),
      }))
      await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
      const service = page.locator('[role="button"][aria-controls^="api-service-"]').first()
      await expect(service.locator('.animate-spin')).toHaveCount(0)
      await service.focus()
      await page.keyboard.press('Enter')
      await expect(service).toHaveAttribute('aria-expanded', 'true')
      for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
        const operation = page.locator(`button[aria-controls="api-operation-account-0-${method}"]`)
        await expect(operation).toBeVisible()
        await operation.click()
        await expect(operation).toHaveAttribute('aria-expanded', 'true')
        if (width === 320) {
          const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth)
          expect(documentWidth).toBeLessThanOrEqual(width + 1)
        }
        const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
        expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
          target: node.target.join(' > '),
          detail: node.any[0]?.message,
        })))).toEqual([])
      }
    })
  }
}

for (const theme of ['light', 'dark'] as const) {
  test(`API domain filters retain readable selected states in ${theme} theme`, async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'ob-admin-theme', value: theme, url: baseURL! }])
    await page.goto('/docs/api', { waitUntil: 'domcontentloaded' })
    const filters = page.getByRole('group', { name: 'Filter by domain' })
    await expect.poll(async () => filters.getByRole('button').count()).toBeGreaterThan(1)
    const labels = (await filters.getByRole('button').allTextContents()).slice(1)
    expect(labels.length).toBeGreaterThan(0)
    for (const label of labels) {
      const filter = filters.getByRole('button', { name: label, exact: true })
      await filter.click()
      await expect(filter).toHaveAttribute('aria-pressed', 'true')
      const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze()
      expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
        target: node.target.join(' > '),
        detail: node.any[0]?.message,
      })))).toEqual([])
    }
  })
}
