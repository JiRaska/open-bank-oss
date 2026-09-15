// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme, type OperatorTheme } from './helpers/theme'

const PARTY_ID = '05a02ef1-381c-40e7-b73f-d6855eead42e'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const theme of ['light', 'dark'] as const satisfies readonly OperatorTheme[]) {
  test(`recovers an unavailable Customer 360 portfolio in the ${theme} theme`, async ({ page }) => {
    await setOperatorTheme(page, theme)
    await page.setViewportSize({ width: 320, height: 760 })
    await page.route('**/api/svc/party-service/api/v1/parties/search**', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ data: [{ id: PARTY_ID, legalName: 'Anna Nováková', status: 'ACTIVE', kycStatus: 'VERIFIED' }] }),
    }))
    await page.route(`**/api/customer-360/${PARTY_ID}`, route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ available: true, partyId: PARTY_ID, asOf: '2026-08-22 08:00:00', accountIds: [], domains: [], consents: [], excludedCount: 0 }),
    }))
    let graphRequests = 0
    await page.route(`**/api/customer-360/${PARTY_ID}/graph`, route => {
      graphRequests += 1
      return route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(graphRequests === 1
          ? { accounts: [], cards: [], notifications: [], devices: [], lendingApplications: [], amlCases: [], documents: [], unavailable: ['lending'], truncated: [] }
          : { accounts: [], cards: [], notifications: [], devices: [], lendingApplications: [{ id: 'loan-1', status: 'APPROVED' }], amlCases: [], documents: [], unavailable: [], truncated: [] }),
      })
    })

    await page.goto('/customer-360')
    await page.getByRole('textbox', { name: /Vyhledat stranu|Search parties/ }).fill('Anna Nováková')
    await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
    await page.getByRole('button', { name: /Vybrat|Select/ }).click()

    const portfolio = page.locator('[data-customer-portfolio]')
    await expect(portfolio.getByRole('status')).toContainText(/Nelze zjistit|Unavailable/)
    await portfolio.getByRole('button', { name: /Načíst znovu|Retry/ }).click()
    await expect(portfolio.getByText('APPROVED')).toBeVisible()
    expect(graphRequests).toBe(2)
    expect(await portfolio.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)

    const results = await new AxeBuilder({ page })
      .include('[data-customer-portfolio]')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(results.violations).toEqual([])
  })
}
