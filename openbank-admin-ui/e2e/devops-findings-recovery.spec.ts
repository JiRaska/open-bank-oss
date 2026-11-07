// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

for (const language of ['en', 'cs'] as const) {
  test(`DevOps findings stay unknown during agent outage and recover (${language})`, async ({ page, context, baseURL }) => {
    await signInAsOperator(context, baseURL!)
    await context.addCookies([{ name: 'openbank-admin-lang', value: language, url: baseURL! }])

    await page.route('**/api/devops/dora', route => route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        overall: null,
        metrics: {
          deploymentFrequency: { level: null, perDay: null, description: null },
          leadTime: { level: null, hours: null, description: null },
          changeFailureRate: { level: null, pct: null, description: null },
          mttr: { level: null, hours: null, description: null },
        },
        recentDeployments: [], sources: { git: false, prometheus: false },
        collectedAt: '2026-09-16T10:00:00Z',
      }),
    }))

    let requests = 0
    let responseState: 'unavailable' | 'malformed' | 'malformed-row' | 'available' = 'unavailable'
    await page.route('**/api/devops/insights', route => {
      requests += 1
      return route.fulfill({
        contentType: 'application/json',
        body: responseState === 'malformed'
          ? '{not valid json'
          : responseState === 'malformed-row'
            ? JSON.stringify({ findings: [{}], available: true })
            : JSON.stringify({ findings: [], available: responseState === 'available' }),
      })
    })

    await page.goto('/devops', { waitUntil: 'domcontentloaded' })
    const workspace = page.getByLabel(/DevOps findings workspace|Pracovní plocha DevOps nálezů/)
    await expect(workspace).toBeVisible({ timeout: 15_000 })
    await expect(workspace).toContainText(language === 'cs'
      ? 'DevOps nálezy nejsou dostupné. Aktuální stav nelze potvrdit.'
      : 'DevOps findings are unavailable. Current health cannot be confirmed.')
    await expect(workspace).not.toContainText(/pipeline healthy|pipeline v pořádku/i)

    const requestsBeforeRecovery = requests
    responseState = 'malformed'
    await page.getByRole('button', { name: /Refresh DevOps metrics|Obnovit DevOps metriky/ }).click()
    await expect(workspace).toContainText(language === 'cs'
      ? 'DevOps nálezy nejsou dostupné. Aktuální stav nelze potvrdit.'
      : 'DevOps findings are unavailable. Current health cannot be confirmed.')
    await expect(page.getByText(language === 'cs' ? 'Chybovost nasazení' : 'Change Failure Rate', { exact: true })).toBeVisible()

    responseState = 'malformed-row'
    await page.getByRole('button', { name: /Refresh DevOps metrics|Obnovit DevOps metriky/ }).click()
    await expect(workspace).toContainText(language === 'cs'
      ? 'DevOps nálezy nejsou dostupné. Aktuální stav nelze potvrdit.'
      : 'DevOps findings are unavailable. Current health cannot be confirmed.')
    await expect(page.getByText(language === 'cs' ? 'Chybovost nasazení' : 'Change Failure Rate', { exact: true })).toBeVisible()

    responseState = 'available'
    await page.getByRole('button', { name: /Refresh DevOps metrics|Obnovit DevOps metriky/ }).click()
    await expect(workspace).toContainText(language === 'cs' ? 'Žádné aktivní DevOps nálezy.' : 'No active DevOps findings.')
    await expect(workspace).not.toContainText(/pipeline healthy|pipeline v pořádku/i)
    expect(requests).toBeGreaterThan(requestsBeforeRecovery)
  })
}


test('DORA renders while the optional findings endpoint is still pending', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/devops/dora', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      overall: null,
      metrics: {
        deploymentFrequency: { level: null, perDay: null, description: null },
        leadTime: { level: null, hours: null, description: null },
        changeFailureRate: { level: null, pct: null, description: null },
        mttr: { level: null, hours: null, description: null },
      },
      recentDeployments: [], sources: { git: false, prometheus: false },
      collectedAt: '2026-09-16T10:00:00Z',
    }),
  }))

  let releaseFindings!: () => void
  const findingsPending = new Promise<void>(resolve => { releaseFindings = resolve })
  await page.route('**/api/devops/insights', async route => {
    await findingsPending
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, findings: [] }) })
  })

  await page.goto('/devops', { waitUntil: 'domcontentloaded' })
  await expect(page.getByText('Change Failure Rate', { exact: true })).toBeVisible()
  await expect(page.getByLabel(/DevOps findings workspace/)).toContainText('Checking DevOps findings…')
  releaseFindings()
  await expect(page.getByLabel(/DevOps findings workspace/)).toContainText('No active DevOps findings.')
})


test('a late findings response from an older refresh cannot replace newer evidence', async ({ page, context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/devops/dora', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({
      overall: null,
      metrics: {
        deploymentFrequency: { level: null, perDay: null, description: null },
        leadTime: { level: null, hours: null, description: null },
        changeFailureRate: { level: null, pct: null, description: null },
        mttr: { level: null, hours: null, description: null },
      },
      recentDeployments: [], sources: { git: false, prometheus: false },
      collectedAt: '2026-09-16T10:00:00Z',
    }),
  }))

  let releaseOldResponses!: () => void
  const oldResponsesPending = new Promise<void>(resolve => { releaseOldResponses = resolve })
  let refreshing = false
  const currentFinding = {
    id: 'new-finding', detector: 'D4_DEPLOY_HEALTH', severity: 'CRITICAL',
    detectedAt: '2026-09-16T09:00:00Z', title: 'Current deployment finding',
    rawMetricValue: 1, threshold: 0, affectedResource: 'admin-ui',
    doraMetricImpacted: null, rootCause: null, remediationKind: 'NONE',
    proposalPrUrl: null, proposedRemediation: null, status: 'OPEN',
    diagnosedAt: null, proposedAt: null,
  }
  await page.route('**/api/devops/insights', async route => {
    if (refreshing) {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, findings: [currentFinding] }) })
      return
    }
    await oldResponsesPending
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ available: true, findings: [] }) })
  })

  await page.goto('/devops', { waitUntil: 'domcontentloaded' })
  const workspace = page.getByLabel(/DevOps findings workspace/)
  await expect(page.getByText('Change Failure Rate', { exact: true })).toBeVisible()
  await expect(workspace).toContainText('Checking DevOps findings…')
  refreshing = true
  await page.getByRole('button', { name: /Refresh DevOps metrics/ }).click()
  await expect(workspace).toContainText('Current deployment finding')
  releaseOldResponses()
  await expect(workspace).toContainText('Current deployment finding')
  await expect(workspace).not.toContainText('No active DevOps findings.')
})
