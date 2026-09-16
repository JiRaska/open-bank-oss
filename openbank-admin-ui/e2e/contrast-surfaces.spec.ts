// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { waitForSettledShell } from './helpers/shell'

const ROUTES = ['/campaigns/new', '/campaigns/referrals', '/docs/service-map', '/services', '/settings'] as const

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/api/**', route => {
    if (new URL(route.request().url()).pathname.startsWith('/api/auth/')) return route.continue()
    return route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"temporarily unavailable"}' })
  })
})

async function scanTheme(page: import('@playwright/test').Page, dark: boolean, selector: string) {
  await page.locator('html').evaluate((element, enabled) => element.classList.toggle('dark', enabled), dark)
  const classes = (await page.locator('html').getAttribute('class') ?? '').split(/\s+/)
  expect(classes.includes('dark')).toBe(dark)
  if (dark) await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
  const accentText = await page.locator('html').evaluate(element => getComputedStyle(element).getPropertyValue('--accent-text').trim())
  expect(accentText).toBe(dark ? '#c7d2fe' : '#4338ca')
  const scan = await new AxeBuilder({ page }).include(selector).withRules(['color-contrast']).analyze()
  expect(scan.violations, scan.violations.flatMap(violation => violation.nodes.map(node =>
    `${node.target.join(' ')}: ${node.failureSummary}`,
  )).join('\n')).toEqual([])
}

for (const route of ROUTES) {
  test(`${route} keeps rendered fallback content readable in both themes`, async ({ page }) => {
    await page.goto(route, { waitUntil: 'domcontentloaded' })
    await waitForSettledShell(page)
    for (const dark of [false, true]) await scanTheme(page, dark, '#main-content')
  })
}

test('published MGM programme card is readable in both themes', async ({ page }) => {
  await page.route('**/api/referral-programs', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ state: 'ok', items: [{
      id: 'mgm-1', name: 'A trusted introduction', version: 1, rewardAmount: 100,
      currency: 'CZK', qualifyingEvent: 'First active account',
      attributionWindowEndsAt: '2026-12-31T00:00:00Z', status: 'PUBLISHED', checker: 'reviewer',
    }] }),
  }))
  await page.goto('/campaigns/referrals', { waitUntil: 'domcontentloaded' })
  await waitForSettledShell(page)
  await expect(page.locator('[data-referral-program]')).toBeVisible()
  for (const dark of [false, true]) await scanTheme(page, dark, '[data-referral-program]')
})
