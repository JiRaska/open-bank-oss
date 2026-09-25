// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('/devops/sdlc — interactive delivery journey', () => {
  test('catalog reflects the manifest and exposes an exact, shareable gate detail', async ({ page }) => {
    const response = await page.request.get('/api/devops/gate-catalog')
    expect(response.ok()).toBe(true)
    const manifest = await response.json()
    await page.goto('/devops/sdlc?gate=api-contract-gate#gate-catalog')
    const catalog = page.locator('#gate-catalog')
    await expect(catalog.getByText(String(manifest.totals.all), { exact: true })).toBeVisible()
    await expect(catalog.getByRole('heading', { name: /Každá skutečná CI brána|Every actual CI gate/i })).toBeVisible()
    await expect(catalog.getByRole('complementary', { name: /Detail CI brány|CI gate detail/i })).toContainText('api-contract-gate')

    await catalog.getByRole('textbox', { name: /Hledat bránu|Search gates/i }).fill('schema-compat-gate')
    await expect(catalog.getByRole('button', { name: /schema-compat-gate/i })).toHaveCount(1)
    await catalog.getByRole('button', { name: /schema-compat-gate/i }).click()
    await expect(page).toHaveURL(/gate=schema-compat-gate/)
    await expect(catalog.getByRole('link', { name: /Otevřít přesnou definici|Open exact definition/i })).toHaveAttribute('href', /gates\.yaml#L\d+/)

    await page.getByRole('button', { name: /G3.*(API|data)/i }).click()
    await page.getByRole('link', { name: /api-contract-gate/i }).click()
    await expect(page).toHaveURL(/gate=api-contract-gate/)
    await expect(catalog.getByRole('complementary', { name: /Detail CI brány|CI gate detail/i })).toContainText('api-contract-gate')
  })

  test('explains one stage from developer, DevOps, and business perspectives', async ({ page }) => {
    await page.goto('/devops/sdlc')
    await expect(page.getByRole('heading', { level: 1, name: /Od nápadu k bezpečnému provozu|From idea to safe operation/i })).toBeVisible()

    const verifyStage = page.getByRole('button', { name: /03 · (CI|CI).*Dokaž kvalitu|03 · CI.*Prove quality/i })
    await verifyStage.click()
    await expect(verifyStage).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByRole('region').filter({ hasText: /Co v této fázi dělám já|What do I do/i })).toContainText(/příčinu selhání|cause of a failure/i)

    await page.getByRole('button', { name: 'DevOps', exact: true }).click()
    await expect(page.getByText(/fail-closed brány|fail-closed gates/i)).toBeVisible()
    await page.getByRole('button', { name: 'Business', exact: true }).click()
    await expect(page.getByText(/průkazný verdikt|evidence-backed verdict/i)).toBeVisible()

    await page.getByRole('button', { name: /G5.*Bezpečnost|G5.*Security/i }).click()
    await expect(page.getByRole('region', { name: /Vysvětlení quality gate|Quality gate explanation/i }))
      .toContainText(/Gitleaks.*CodeQL.*Trivy/i)

    const innovations = page.getByRole('region', { name: /Důvěra není slogan|Trust is a chain/i })
    await expect(innovations.getByText(/Governance-as-code|Governance as code/i)).toBeVisible()
    await expect(innovations.getByText(/Documentation-as-code|Documentation as code/i)).toBeVisible()
    await expect(innovations.getByText(/SBOM svázaný s image|SBOM bound to the image/i)).toBeVisible()
    await expect(innovations.getByRole('link', { name: /Prohlédnout obsah SBOM|Explore SBOM contents/i }))
      .toHaveAttribute('href', '/system/inventory')
  })

  test('is keyboard operable and meets WCAG A/AA in light and dark themes', async ({ page }) => {
    await page.goto('/devops/sdlc')
    await expect(page.getByRole('button', { name: /Pozastavit animaci|Pause animation|Spustit animaci|Play animation/i })).toBeVisible()
    const detail = page.getByRole('region', { name: /Detail fáze|Stage detail/i })
    await detail.getByRole('link').focus()
    await expect(page.getByRole('button', { name: /Spustit animaci|Play animation/i })).toBeVisible()
    await expect(detail).toHaveAttribute('aria-live', 'polite')
    const releaseStage = page.getByRole('button', { name: /05 · RELEASE.*(Vytvoř důvěryhodný artefakt|Create a trusted artifact)/i })
    await releaseStage.focus()
    await releaseStage.press('Enter')
    await expect(releaseStage).toHaveAttribute('aria-pressed', 'true')

    for (const theme of ['light', 'dark'] as const) {
      await page.evaluate(selectedTheme => {
        document.documentElement.classList.toggle('dark', selectedTheme === 'dark')
        document.documentElement.dataset.theme = selectedTheme
      }, theme)
      await page.waitForTimeout(100)
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze()
      expect(results.violations).toEqual([])
    }
  })
})
