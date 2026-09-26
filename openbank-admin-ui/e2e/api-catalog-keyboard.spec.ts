// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/services/health', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ services: [] }),
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
      paths: { '/api/v1/example': { get: { summary: 'Read an example', responses: { 200: { description: 'OK' } } } } },
    }),
  }))
  await page.route('**/api/svc/**', route => route.fulfill({ status: 503, body: '{}' }))
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
