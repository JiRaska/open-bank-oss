// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

test.setTimeout(90_000)

for (const language of ['cs', 'en']) {
  for (const theme of ['light', 'dark'] as const) {
    test(`game management is reachable from loyalty and sidebar (${language}, ${theme})`, async ({ page, context, baseURL }, testInfo) => {
      await signInWithRoles(context, baseURL!, ['ROLE_ADMIN'])
      await context.addCookies([
        { name: 'openbank-admin-lang', value: language, url: baseURL! },
        { name: 'ob-admin-theme', value: theme, url: baseURL! },
      ])
      await page.emulateMedia({ colorScheme: theme })
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.route('**/api/loyalty', route => route.fulfill({ json: {
        state: 'unreachable', benefits: [], earnSources: [], provisioning: null,
      } }))
      await page.route('**/api/game/catalog', route => route.fulfill({ json: { revision: 0, levels: [] } }))
      await page.goto('/loyalty')
      const name = language === 'cs' ? 'Lví stezka — správa hry' : 'Lion Quest — game management'
      const entry = page.getByRole('navigation', { name: language === 'cs' ? 'Sekce Lípy' : 'Lípa sections' }).getByRole('link', { name })
      await expect(entry).toBeVisible()
      await expect(entry).toHaveAttribute('href', '/loyalty/game')
      const sidebar = page.getByRole('complementary', { name: language === 'cs' ? 'Hlavní navigace' : 'Main navigation' })
      await expect(sidebar.getByRole('link', { name })).toBeVisible()
      await page.screenshot({ path: testInfo.outputPath('loyalty-game-entry.png') })
      await entry.click()
      await expect(page).toHaveURL(/\/loyalty\/game$/)
      await expect(page.getByRole('main').getByRole('heading', { level: 1 })).toHaveText(language === 'cs' ? 'Lví stezka — správa kol' : 'Lion Quest — chapters')
      await expect(sidebar.getByRole('link', { name })).toHaveAttribute('aria-current', 'page')
      await sidebar.getByRole('link', { name: language === 'cs' ? 'Lípa (věrnost)' : 'Lípa (Loyalty)', exact: true }).click()
      await sidebar.getByRole('link', { name }).click()
      await expect(page).toHaveURL(/\/loyalty\/game$/)
    })
  }
}

test('viewer sees loyalty without game-management shortcuts', async ({ page, context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_VIEWER'])
  await page.route('**/api/loyalty', route => route.fulfill({ json: {
    state: 'unreachable', benefits: [], earnSources: [], provisioning: null,
  } }))
  await page.goto('/loyalty')
  await expect(page.getByRole('main').getByRole('heading', { level: 1 })).toHaveText('Lípa')
  await expect(page.locator('a[href="/loyalty/game"]')).toHaveCount(0)
})
