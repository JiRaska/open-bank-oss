// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

for (const [width, language] of [[1440, 'en'], [390, 'en'], [320, 'en'], [320, 'cs']] as const) test(`keeps risk KPIs in place when evidence fails at ${width}px (${language})`, async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await context.addCookies([{ name: 'openbank-admin-lang', value: language, url: baseURL! }])
  await page.setViewportSize({ width, height: 900 })

  let releaseFailures!: () => void
  const heldFailures = new Promise<void>(resolve => { releaseFailures = resolve })
  await page.route('**/api/services/lending-service/**', async route => {
    await heldFailures
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"synthetic unavailable"}' })
  })

  await page.goto('/lending/risk', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText(language === 'cs' ? 'Ověřuji podklady kreditního rizika' : 'Validating credit-risk evidence')).toBeVisible()
  const kpis = page.locator('#main-content .grid-4').first()
  const before = await kpis.boundingBox()
  expect(before).not.toBeNull()

  releaseFailures()
  const alert = page.locator('#main-content [role="alert"]').filter({ hasText: language === 'cs' ? 'Podklady kreditního rizika nejsou úplné' : 'Credit-risk evidence is incomplete' })
  await expect(alert).toContainText(language === 'cs' ? '5/5 zdrojů nedostupných nebo neplatných' : '5/5 feeds unavailable or invalid')
  const after = await kpis.boundingBox()
  expect(after).not.toBeNull()
  expect(Math.abs(after!.y - before!.y)).toBeLessThan(1)
  await alert.getByText(language === 'cs' ? 'Zobrazit dotčené zdroje' : 'Show affected feeds').click()
  await expect(alert.getByText('/risk/policy')).toBeVisible()
  if (width === 320 && language === 'cs') {
    const scan = await new AxeBuilder({ page }).include('#main-content [role="alert"]').withTags(['wcag2a', 'wcag2aa']).analyze()
    expect(scan.violations).toEqual([])
  }
})
