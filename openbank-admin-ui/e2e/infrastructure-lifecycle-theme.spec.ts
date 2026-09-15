// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'
import { setOperatorTheme } from './helpers/theme'

const lifecycleBase = {
  running: { version: '1.0.0', source: 'runtime' },
  lifecycle: { available: false, product: null, reason: 'catalogue unavailable' },
  cve: { scanned: true, critical: 0, high: 1, medium: 0, low: 0, total: 1, top: [] },
}

test('uses adaptive danger and warning text for infrastructure lifecycle summaries', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await setOperatorTheme(page, 'dark')
  await page.route('**/api/infra/status', route => route.fulfill({
    json: { postgres: { id: 'postgres', status: 'UP', latencyMs: 8, checkedAt: '2026-09-15T08:00:00Z' } },
  }))
  await page.route('**/api/infra/kafka-topics', route => route.fulfill({ json: { topics: [], clusterName: 'sandbox' } }))
  await page.route('**/api/infra/lifecycle', route => route.fulfill({
    json: {
      components: [
        { ...lifecycleBase, id: 'postgres', urgency: 'vulnerable', upgrade: { patchAvailable: false, majorAvailable: false, target: null, releaseNotesUrl: null } },
        { ...lifecycleBase, id: 'kafka', urgency: 'patch-available', upgrade: { patchAvailable: true, majorAvailable: false, target: '1.0.1', releaseNotesUrl: null } },
      ],
    },
  }))

  await page.goto('/infrastructure')
  const danger = page.getByText('1 need attention')
  const warning = page.getByText('1 upgradable')
  await expect(danger).toBeVisible()
  await expect(warning).toBeVisible()

  const colors = await page.evaluate(() => {
    const probe = document.createElement('span')
    document.body.append(probe)
    probe.style.color = 'var(--danger-text)'
    const danger = getComputedStyle(probe).color
    probe.style.color = 'var(--warning-text)'
    const warning = getComputedStyle(probe).color
    probe.remove()
    return { danger, warning }
  })
  await expect(danger).toHaveCSS('color', colors.danger)
  await expect(warning).toHaveCSS('color', colors.warning)
})
