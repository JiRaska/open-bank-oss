// SPDX-License-Identifier: Apache-2.0

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const theme of ['light', 'dark'] as const) {
  test(`reliability status evidence is readable in ${theme} theme`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await context.addInitScript(chosen => localStorage.setItem('ob-admin-theme', chosen), theme)
    await page.route('**/api/pyrra/summary', route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        available: true, configured: 1, monitored: 1,
        objectives: [{ name: 'payments', budgetRemaining: 0.1, availability: 0.99 }],
      }),
    }))
    await page.route('**/api/temporal/status', route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        available: true, temporalDeployed: true,
        metrics: { workflows: { scheduled1h: 4, completed1h: 3, failed1h: 1, timedOut1h: 0 } },
      }),
    }))
    await page.route('**/api/tempo/api/search**', route => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ traces: [{ traceID: '0123456789abcdef', durationMs: 200, rootTraceName: 'payment' }] }),
    }))

    await page.goto('/system/health', { waitUntil: 'domcontentloaded' })
    const brief = page.locator('section[aria-labelledby="operational-evidence-title"]')
    await expect(brief.getByText('10%')).toBeVisible()
    await expect(brief.getByText('1 failed')).toBeVisible()
    await expect(brief.getByText('200 ms')).toBeVisible()
    await expect(page.locator('html')).toHaveClass(theme === 'dark' ? /dark/ : /^(?!.*dark)/)
    await page.waitForTimeout(400)

    const scan = await new AxeBuilder({ page }).include('section[aria-labelledby="operational-evidence-title"]').withRules(['color-contrast']).analyze()
    expect(scan.violations.flatMap(violation => violation.nodes.map(node => ({
      target: node.target.join(' > '), message: node.any[0]?.message,
    })))).toEqual([])
  })
}

test('a slow SLO source does not withhold workflow and trace evidence', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  let releasePyrra!: () => void
  const pendingPyrra = new Promise<void>(resolve => { releasePyrra = resolve })
  await page.route('**/api/pyrra/summary', async route => {
    await pendingPyrra
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
  })
  await page.route('**/api/temporal/status', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({
      available: true, temporalDeployed: true,
      metrics: { workflows: { scheduled1h: 4, completed1h: 3, failed1h: 0, timedOut1h: 0 } },
    }),
  }))
  await page.route('**/api/tempo/api/search**', route => route.fulfill({
    status: 200, contentType: 'application/json',
    body: JSON.stringify({ traces: [{ traceID: '0123456789abcdef', durationMs: 1500, rootTraceName: 'payment' }] }),
  }))

  try {
    await page.goto('/system/health', { waitUntil: 'domcontentloaded' })
    const brief = page.locator('section[aria-labelledby="operational-evidence-title"]')
    await expect(brief.getByText('3 OK')).toBeVisible()
    await expect(brief.getByText('1.50 s')).toBeVisible()
    await expect(brief.getByRole('button', { name: 'Refresh operational evidence' })).toHaveAttribute('aria-busy', 'true')
  } finally {
    releasePyrra()
  }
})
