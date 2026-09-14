// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

// ADR-0076 Layer 2 — E2E: /services page docs coverage
//
// These tests verify that the "Service Documentation" page correctly computes
// and renders the X/Y coverage counter, and that services are correctly
// classified as documented vs undocumented. All service HTTP calls are
// intercepted via page.route() — no live services required.

import AxeBuilder from '@axe-core/playwright'
import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// The console gates every route on an Auth.js session (src/proxy.ts); there is no
// Keycloak in this environment, so each test signs in via a minted session cookie instead
// of a real OIDC flow (see helpers/auth.ts for why this is safe).
test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

// Minimal DocsIndex payload that satisfies hasDocs=true (items.length > 0)
const DOCS_INDEX = {
  service: 'account-service',
  source: 'live' as const,
  requestedLang: 'en',
  availableLanguages: ['en', 'cs'],
  items: [
    { slug: 'README', lang: 'en', title: 'Account Service', availableLanguages: ['en', 'cs'] },
    { slug: '01-overview', lang: 'en', title: 'Overview', availableLanguages: ['en', 'cs'] },
    { slug: '06-compliance', lang: 'en', title: 'Compliance', availableLanguages: ['en', 'cs'] },
  ],
}

test.describe('/services — Service Documentation page', () => {
  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme explains serverless tiers accessibly`, async ({ page }) => {
      await page.route('**/api/services/health', route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
      )
      await page.route('**/api/services/*/docs', route =>
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found', service: 'x' }) })
      )

      await page.addInitScript(selectedTheme => {
        window.localStorage.setItem('openbank-theme', selectedTheme)
      }, theme)
      await page.goto('/services')

      const disclosure = page.getByRole('button', { name: /Serverless tiers.*scale-to-zero|Serverless tiery.*škálování na nulu/i })
      await expect(disclosure).toHaveAttribute('aria-expanded', 'false')
      await disclosure.click()
      await expect(disclosure).toHaveAttribute('aria-expanded', 'true')
      await expect(page.getByText(/Every service falls into one tier|Každá služba spadá do jednoho tieru/i)).toBeVisible()
      await expect(page.getByText('T0', { exact: true })).toBeVisible()
      await expect(page.getByText('T3', { exact: true })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }

  for (const theme of ['light', 'dark'] as const) {
    test(`${theme} theme exposes catalog drift without hiding the page`, async ({ page }) => {
      await page.route('**/api/services/health', route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
      )
      await page.route('**/api/services/*/docs', route =>
        route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found', service: 'x' }) })
      )
      await page.route('**/api/catalog/services', route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [{ short: 'future-bank-service', kind: 'service' }] }) })
      )
      await page.addInitScript(selectedTheme => {
        window.localStorage.setItem('openbank-theme', selectedTheme)
      }, theme)

      await page.goto('/services')

      const warning = page.getByRole('status').filter({ hasText: 'future-bank-service' })
      await expect(warning).toBeVisible()
      await expect(page.getByRole('heading', { level: 1, name: /Service Documentation|Dokumentace služeb/i })).toBeVisible()

      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    })
  }

  test('shows correct coverage count when all services have docs', async ({ page }) => {
    // Mock health (static, empty — page uses STATIC_CANDIDATES as the list)
    await page.route('**/api/services/health', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
    )
    // All docs requests return docs
    await page.route('**/api/services/*/docs', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(DOCS_INDEX) })
    )

    await page.goto('/services')
    // Wait for loading to finish (coverage counter should appear). Scoped to <main> —
    // Next.js dev mode can mount a hidden error-overlay pagination badge (also "N/M"
    // shaped) outside the app shell, which would otherwise collide in strict mode.
    await expect(page.locator('main').getByText(/\d+\s*\/\s*\d+/)).toBeVisible({ timeout: 10_000 })

    const counterText = await page.locator('main').getByText(/\d+\s*\/\s*\d+/).first().textContent()
    // All services documented → numerator == denominator
    const match = counterText?.match(/(\d+)\s*\/\s*(\d+)/)
    expect(match).not.toBeNull()
    const [, documented, total] = match!.map(Number)
    expect(documented).toBeGreaterThan(0)
    expect(documented).toBe(total)
  })

  test('shows 0/N when no services have docs', async ({ page }) => {
    await page.route('**/api/services/health', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
    )
    // All docs requests return 404 (no docs)
    await page.route('**/api/services/*/docs', route =>
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'docs not available', service: 'x' }) })
    )

    await page.goto('/services')
    await expect(page.locator('main').getByText(/\d+\s*\/\s*\d+/)).toBeVisible({ timeout: 10_000 })

    const counterText = await page.locator('main').getByText(/\d+\s*\/\s*\d+/).first().textContent()
    const match = counterText?.match(/(\d+)\s*\/\s*(\d+)/)
    expect(match).not.toBeNull()
    const documented = Number(match![1])
    expect(documented).toBe(0)
  })

  test('documented services appear in the documented section', async ({ page }) => {
    await page.route('**/api/services/health', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
    )
    // Playwright uses LIFO: last-registered handler wins for a given URL.
    // Register the catch-all 404 first so the specific account handler (registered
    // second) takes precedence for /api/services/account/docs requests.
    await page.route('**/api/services/*/docs', route =>
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'docs not available', service: 'x' }) })
    )
    // Only 'account' has docs — registered last, so it wins for this URL (LIFO).
    await page.route('**/api/services/account/docs', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...DOCS_INDEX, service: 'account-service' }) })
    )

    await page.goto('/services')
    // Wait for the documented section to appear
    await expect(page.getByText(/Account Service/i)).toBeVisible({ timeout: 10_000 })
  })

  test('page renders inside app shell (sidebar + header)', async ({ page }) => {
    await page.route('**/api/services/health', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [], source: 'static' }) })
    )
    await page.route('**/api/services/*/docs', route =>
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found', service: 'x' }) })
    )

    await page.goto('/services')
    // App shell rule (ADR-0076 / graceful-state guard): sidebar must be visible.
    // The Sidebar component renders <aside><nav>…, so match the outer landmark only —
    // matching both would be a strict-mode violation (2 elements for 1 locator).
    await expect(page.locator('aside:visible')).toBeVisible({ timeout: 10_000 })
  })

  test('gracefully handles health endpoint failure (falls back to static candidates)', async ({ page }) => {
    // Health endpoint down
    await page.route('**/api/services/health', route =>
      route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    )
    await page.route('**/api/services/*/docs', route =>
      route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found', service: 'x' }) })
    )

    await page.goto('/services')
    // Should still render without crashing — coverage counter visible (0/N with static list)
    await expect(page.locator('main').getByText(/\d+\s*\/\s*\d+/)).toBeVisible({ timeout: 10_000 })
    // No raw error text leaked (graceful-state rule)
    await expect(page.getByText(/HTTP 5\d\d/)).not.toBeVisible()
  })
})
