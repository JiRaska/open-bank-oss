// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFile } from 'node:fs/promises'
import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY_ID = '11111111-1111-4111-8111-111111111111'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('rejects malformed loyalty evidence and recovers with verified customer data', async ({ page }) => {
  await page.route('**/api/loyalty', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      state: 'ok',
      benefits: [],
      earnSources: [],
      provisioning: {
        at: '2026-09-09T08:00:00Z',
        outstandingLeaves: 1200,
        annualCapPerParty: 1000,
        ruleVersion: 'v1',
      },
    }),
  }))

  let lookup = 0
  await page.route('**/api/loyalty/party/*', route => {
    lookup += 1
    const body = lookup === 1
      ? { state: 'ok', partyId: '22222222-2222-4222-8222-222222222222', balance: 0, earnedThisYear: 0, earnedTotal: 0, nextExpiry: null, history: [] }
      : { state: 'ok', partyId: PARTY_ID, balance: 80, earnedThisYear: 120, earnedTotal: 300, nextExpiry: null, history: [] }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/loyalty')
  await page.getByRole('button', { name: /Customer|Klient/ }).click()
  await page.getByLabel(/Customer UUID|UUID klienta/).fill(PARTY_ID)
  const submit = page.getByRole('button', { name: /Look up|Vyhledat/ })

  await submit.click()
  await expect(page.getByText(/Loyalty-service is not responding|Loyalty-service neodpovídá/)).toBeVisible()
  await expect(page.getByText('80', { exact: true })).toHaveCount(0)

  await submit.click()
  await expect(page.getByText('80', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /Open Customer 360|Otevřít Customer 360/ })).toHaveAttribute('href', `/customer-360?partyId=${PARTY_ID}`)
})

for (const language of ['en', 'cs']) {
  test(`marketing workspace recovers, filters and exports a brief (${language})`, async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'openbank-admin-lang', value: language, url: baseURL! }])
    await page.setViewportSize({ width: 1440, height: 1100 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    let available = false
    await page.route('**/api/loyalty', route => {
      return route.fulfill({ json: !available
        ? { state: 'unreachable', benefits: [], earnSources: [], provisioning: null }
        : { state: 'ok', benefits: [{ id: 'FEE_WAIVER', description: 'Monthly fee waiver', engine: 'billing', priceLeaves: 50, validityDays: 30 }], earnSources: [{ id: 'SAVINGS_GOAL', leaves: 20, validityDays: 730 }], provisioning: { at: '2026-09-09T08:00:00Z', outstandingLeaves: 1200, annualCapPerParty: 1000, ruleVersion: 'v1' } },
      })
    })
    await page.goto('/loyalty')
    await expect(page.getByRole('heading', { name: /Your programme at a glance|Vše důležité/ })).toBeVisible()
    await expect(page.getByText(/Live data unavailable|Živá data nedostupná/)).toBeVisible()
    const nav = page.getByRole('navigation', { name: /Lípa sections|Sekce Lípy/ })
    await nav.getByRole('button', { name: /Catalogues|Katalogy/, exact: true }).click()
    available = true
    await page.getByRole('button', { name: /Try again|Zkusit znovu/ }).click()
    await expect(page.getByText('FEE_WAIVER', { exact: true })).toBeVisible()
    await page.screenshot({ path: test.info().outputPath('lipa-catalogues.png'), fullPage: true, animations: 'disabled' })
    const search = page.getByRole('searchbox')
    await search.fill('no-such-benefit')
    await expect(page.getByText(/No matching benefits|Žádné odpovídající benefity/)).toBeVisible()
    await expect(page.getByText('FEE_WAIVER', { exact: true })).toHaveCount(0)
    await search.fill('')
    await page.getByLabel(/Change brief \(no customer|Zadání změny \(bez osobních/).fill('Review the savings reward for the autumn campaign.')
    const downloadEvent = page.waitForEvent('download')
    await page.getByRole('button', { name: /Download brief|Stáhnout zadání/ }).click()
    const download = await downloadEvent
    expect(download.suggestedFilename()).toBe('lipa-proposal.txt')
    expect(await readFile((await download.path())!, 'utf8')).toContain('Review the savings reward for the autumn campaign.')
    for (const name of [/Programme rules|Pravidla programu/, /Finance and law|Finance a právo/, /^AI$|Umělá inteligence/, /^Customer$|^Klient$/, /^Overview$|^Přehled$/]) {
      await nav.getByRole('button', { name }).click()
      await expect(nav.getByRole('button', { name })).toHaveAttribute('aria-current', 'page')
      await page.screenshot({ path: test.info().outputPath(`section-${name.source.replace(/[^a-zA-Z]/g, '')}.png`), fullPage: true, animations: 'disabled' })
    }
    // A regression for the unlayered global reset that erased Tailwind padding.
    expect(await nav.getByRole('button').first().evaluate(el => parseFloat(getComputedStyle(el).paddingTop))).toBeGreaterThan(0)
    await page.screenshot({ path: test.info().outputPath('lipa-overview.png'), fullPage: true, animations: 'disabled' })
    const lightScan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(lightScan.violations).toEqual([])
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    await page.screenshot({ path: test.info().outputPath('lipa-dark.png'), fullPage: true, animations: 'disabled' })
    const darkScan = await new AxeBuilder({ page })
      .include('#main-content')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(darkScan.violations).toEqual([])
    await page.setViewportSize({ width: 390, height: 844 })
    for (const button of await nav.getByRole('button').all()) {
      await button.click()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    }
  })
}
