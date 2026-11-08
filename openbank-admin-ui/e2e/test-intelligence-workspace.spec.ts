// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'
import { workspaceReport, workspaceObservedAt } from './fixtures/test-intelligence-workspace'

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/test-intelligence', route => route.fulfill({ json: workspaceReport() }))
  await page.route('**/api/catalog/services', route => route.fulfill({ json: { services: [] } }))
})

test('puts a grouped matrix first and opens an attributable test in two clicks', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  let explanations = 0
  await page.route('**/api/test-intelligence/explain', route => {
    explanations++
    expect(route.request().postDataJSON()).toEqual({ fingerprint: 'case-1', collectedAt: workspaceObservedAt, language: 'en' })
    return route.fulfill({ json: { fingerprint: 'case-1', collectedAt: workspaceObservedAt, model: 'test-provider', explanation: 'The recorded check passed. <script>window.injected=true</script> Individual steps were not retained.' } })
  })
  await page.goto('/system/tests')
  const matrix = page.getByRole('region', { name: 'Component evidence matrix' })
  await expect(matrix).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('matrix-desktop.png') })
  const bounds = await matrix.boundingBox()
  expect(bounds!.y).toBeLessThan(600)
  await expect(page.getByText('Run summaries and cross-layer maps')).not.toBeVisible()
  expect(explanations).toBe(0)
  await matrix.getByRole('button', { name: 'account · Logic · Passed', exact: true }).click()
  await page.getByRole('button', { name: 'rejects duplicate requests', exact: true }).click()
  const detail = page.getByRole('region', { name: 'Understand this test' })
  await expect(detail).toContainText('Individual steps, input values and expected-versus-actual assertions were not retained')
  await expect(detail.getByRole('link', { name: /Open this test’s exact run/ })).toHaveAttribute('href', /\/runs\/101$/)
  await expect(detail.getByRole('link', { name: 'Test definition at the tested commit' })).toHaveAttribute('href', /\/blob\/a{40}\//)
  await expect(page).toHaveURL(/test=case-1/)
  expect(explanations).toBe(0)
  await detail.getByRole('button', { name: 'Explain this result with AI' }).click()
  await page.screenshot({ path: testInfo.outputPath('test-detail.png') })
  const interpretation = detail.getByRole('complementary', { name: 'AI interpretation of evidence' })
  await expect(interpretation).toContainText('<script>window.injected=true</script>')
  await expect(interpretation.locator('script')).toHaveCount(0)
  expect(explanations).toBe(1)
  await expect(detail).toContainText('original verdict unchanged')
  expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations).toEqual([])
  await page.goBack()
  await expect(page.getByRole('region', { name: 'Selected component tests' })).toBeVisible()
  await page.goBack()
  await expect(matrix).toBeVisible()
})

test('retains results when AI is unavailable and supports a shared detail URL', async ({ page }) => {
  await page.route('**/api/test-intelligence/explain', route => route.fulfill({ status: 503, json: { error: 'explanation_unavailable' } }))
  await page.goto('/system/tests?component=openbank-account-service&kind=unit&test=case-1')
  const detail = page.getByRole('region', { name: 'Understand this test' })
  await detail.getByRole('button', { name: 'Explain this result with AI' }).click()
  await expect(detail.getByRole('status')).toContainText('The original result remains available')
  await expect(detail.getByText('Passed', { exact: true }).first()).toBeVisible()
})

test('keeps navigation and the matrix usable on a phone without page overflow', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/system/tests')
  await expect(page.getByRole('region', { name: 'Component evidence matrix' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('matrix-mobile.png') })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('button', { name: 'Needs attention', exact: true }).click()
  await expect(page.getByRole('button', { name: 'openbank-account-service', exact: true })).toBeVisible()
  expect((await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()).violations).toEqual([])
})
