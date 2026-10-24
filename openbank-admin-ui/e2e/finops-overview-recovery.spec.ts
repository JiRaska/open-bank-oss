// SPDX-License-Identifier: Apache-2.0
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const lifecycle = {
  currentVersion: '1.36', currentTier: 'standard', daysToStandardEnd: 365, runwayStatus: 'ok',
  standardSupportEnds: '2027-10-19', extendedSupportEnds: '2028-10-19', monthlyEstimate: 73,
  annualEstimate: 876, monthlySavingsVsExtended: 365, annualSavingsVsExtended: 4380,
  minRunwayDays: 180, versions: [], components: [], dataSource: 'snapshot', lastRefreshed: '2026-10-19', adrRef: 'ADR-0054',
}
const costs = { available: true, currency: 'USD', periodStart: '2026-10-01', periodEnd: '2026-10-19', total: 4321,
  services: [], daily: [], collectedAt: '2026-10-19T10:00:00Z', source: 'snapshot' }

for (const language of ['en', 'cs'] as const) {
  for (const theme of ['light', 'dark'] as const) {
    test(`FinOps isolates failures and recovers in ${language}/${theme}`, async ({ page, context, baseURL }) => {
      await signInAsOperator(context, baseURL!)
      await context.addCookies([{ name: 'openbank-admin-lang', value: language, url: baseURL! }])
      await context.addInitScript(value => localStorage.setItem('ob-admin-theme', value), theme)
      if (language === 'cs') await page.setViewportSize({ width: 390, height: 844 })
      let failCosts = false
      await page.route('**/api/finops/**', route => {
        const endpoint = new URL(route.request().url()).pathname.split('/').pop()
        const body = endpoint === 'lifecycle' ? lifecycle : endpoint === 'costs' ? costs : { available: false }
        return route.fulfill({ status: endpoint === 'costs' && failCosts ? 503 : 200,
          contentType: 'application/json', body: endpoint === 'resources' ? '{' : JSON.stringify(body) })
      })
      await page.goto('/finops')
      const main = page.getByRole('main')
      await expect(main.getByText(language === 'en' ? 'EKS Support Tier' : 'Typ podpory EKS')).toBeVisible()
      await expect(main.getByText(language === 'en' ? '$4,321' : '$4 321', { exact: true })).toBeVisible()
      await expect(main.getByRole('button', { name: language === 'en' ? 'Retry Resource utilisation' : 'Zkusit znovu: Využití zdrojů' })).toBeVisible()
      const refresh = page.getByRole('button', { name: language === 'en' ? 'Refresh FinOps costs' : 'Obnovit FinOps náklady' })
      await expect(refresh).toBeEnabled()
      failCosts = true
      await refresh.click()
      const retry = main.getByRole('button', { name: language === 'en' ? 'Retry Cloud costs' : 'Zkusit znovu: Cloudové náklady' })
      await expect(retry).toBeVisible()
      await expect(main.getByText(language === 'en' ? '$4,321' : '$4 321', { exact: true })).toHaveCount(0)
      failCosts = false
      await retry.click()
      await expect(main.getByText(language === 'en' ? '$4,321' : '$4 321', { exact: true })).toBeVisible()
      await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
      const scan = await new AxeBuilder({ page }).include('main').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
      expect(scan.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.target) }))).toEqual([])
    })
  }
}
