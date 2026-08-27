// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// E2E coverage for the ADR-0208 shared UI primitives, in a real browser with the real
// stylesheet applied.
//
// WHY THIS SUITE EXISTS — it is not "more tests", it covers a specific blind spot.
// PR #2556 shipped three visual regressions that `tsc --noEmit`, `eslint` and the
// mount-only `render-smoke` suite ALL passed:
//   1. score swatches applied `BADGE_CLASS` and overrode width/height inline, but `.badge`
//      sets `padding: 4px 10px; border-radius: 20px` and globals.css sets a global
//      `box-sizing: border-box` — so an 18px swatch had 20px of horizontal padding inside an
//      18px box (zero content width) and a 26px tile rendered as a circle;
//   2. a header icon was silently dropped in a migration;
//   3. StatCard tinted only its label, so a red/green metric value rendered plain.
// None of those are type errors, lint errors, or mount failures. They are only observable
// once CSS is applied and geometry is measured — which is exactly what a browser does and
// jsdom does not. So the assertions below are deliberately about COMPUTED STYLE and
// BOUNDING BOXES, not about text being present.
//
// All BFF traffic is intercepted with page.route(); no live services required.

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

const READINESS = {
  generated_for: 'e2e',
  dimensions: [
    { code: 'C1', name: 'Code' },
    { code: 'C2', name: 'Backup' },
  ],
  services: [
    { service: 'svc-go', money_path: true, scores: { C1: 3, C2: 3 }, evidence: {}, gate: 'GO' },
    { service: 'svc-nogo', money_path: false, scores: { C1: 0, C2: 1 }, evidence: {}, gate: 'NO-GO' },
  ],
}

const CONSENTS = [
  {
    id: 'c1', partyId: '11111111-1111-1111-1111-111111111111',
    granteeId: 'party-service:marketing-comms', granteeType: 'INTERNAL_SERVICE',
    granteeName: 'Party marketing preferences',
    scopes: ['MARKETING_COMMS_EMAIL'], accountIbans: null, status: 'ACTIVE',
    validFrom: '2026-07-01T00:00:00Z', validTo: '2027-06-30T00:00:00Z', createdAt: '2026-07-01T00:00:00Z',
  },
  {
    id: 'c2', partyId: '22222222-2222-2222-2222-222222222222',
    granteeId: 'party-service:marketing-comms', granteeType: 'INTERNAL_SERVICE',
    granteeName: 'Party marketing preferences',
    scopes: ['MARKETING_COMMS_PUSH'], accountIbans: null, status: 'REVOKED',
    validFrom: '2026-01-01T00:00:00Z', validTo: '2027-01-01T00:00:00Z', createdAt: '2026-01-01T00:00:00Z',
  },
]

test.describe('ADR-0208 primitives render with real CSS applied', () => {
  test('dashboard distinguishes current health from unobserved operational and compliance claims', async ({ page }) => {
    await page.route('**/api/services/governance', route =>
      route.fulfill({
        status: 200,
        body: JSON.stringify({ items: [
          { serviceName: 'account-service', dataDomain: 'core' },
          { serviceName: 'ledger-service', dataDomain: 'core' },
          { serviceName: 'aml-service', dataDomain: 'compliance' },
        ] }),
      }),
    )
    await page.route('**/api/services/health', route =>
      route.fulfill({
        status: 200,
        body: JSON.stringify({ services: [
          { name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 },
          { name: 'ledger-service', label: 'Ledger', group: 'core', status: 'DOWN', latencyMs: 28 },
        ] }),
      }),
    )

    await page.goto('/dashboard')

    await expect(page.getByText('Healthy services', { exact: true })).toBeVisible()
    await expect(page.getByText('1/2', { exact: true })).toBeVisible()
    await expect(page.getByText('Average check latency', { exact: true })).toBeVisible()
    // Health discovery does not measure any of these. Rendering a proxy as a fact is unsafe.
    await expect(page.getByText('Security Grade', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Error Rate', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Throughput', { exact: true })).toHaveCount(0)

    await expect(page.getByRole('link', { name: 'Help and documentation' })).toHaveAttribute('href', '/docs')
    await expect(page.locator('header').getByRole('link', { name: 'Approvals' })).toHaveAttribute('href', '/approvals')

    // The approved dashboard direction is deliberately denser than the old generic-card
    // layout: four factual metrics at desktop, then a two-column service overview. These
    // geometry checks make that information hierarchy a browser-observable contract.
    const metricGrid = page.locator('[aria-label="Platform key metrics"]')
    expect(await metricGrid.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length)).toBe(4)
    expect(await metricGrid.locator('.stat-card').first().evaluate(el => getComputedStyle(el).borderTopLeftRadius)).toBe('14px')

    const serviceGrid = page.locator('[aria-label="Services by group"]')
    expect(await serviceGrid.evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length)).toBe(2)

    // The shell is part of the operator experience, not decorative page chrome: the
    // navigation rail and command bar must retain their deliberate working geometry.
    expect(Math.round((await page.locator('#admin-sidebar').boundingBox())!.width)).toBe(264)
    expect(Math.round((await page.locator('header').boundingBox())!.height)).toBe(60)
    expect(await page.locator('.page-header').evaluate(el => getComputedStyle(el).backgroundImage)).toContain('linear-gradient')
  })

  test('tone swatches are square with zero padding, at both sizes', async ({ page }) => {
    await page.route('**/api/prod-readiness', route =>
      route.fulfill({ status: 200, body: JSON.stringify(READINESS) }),
    )
    await page.goto('/system/readiness')

    const swatch = page.locator('.tone-swatch').first()
    await expect(swatch).toBeVisible()

    // The regression: `.badge`'s padding collapsed the content box and its 20px radius
    // turned the tile into a circle. `.tone-swatch` must own its geometry instead.
    const box = await swatch.boundingBox()
    expect(box).not.toBeNull()
    expect(Math.round(box!.width)).toBe(26)
    expect(Math.round(box!.height)).toBe(26)
    expect(await swatch.evaluate(el => getComputedStyle(el).padding)).toBe('0px')
    // A square tile, not a pill: radius must be well under half the 26px side.
    const radius = await swatch.evaluate(el => parseFloat(getComputedStyle(el).borderTopLeftRadius))
    expect(radius).toBeLessThan(13)

    // The small legend variant is where zero content width showed up first.
    const small = page.locator('.tone-swatch-sm').first()
    await expect(small).toBeVisible()
    const smallBox = await small.boundingBox()
    expect(Math.round(smallBox!.width)).toBe(18)
    expect(Math.round(smallBox!.height)).toBe(18)
    // The digit must actually be inside it — zero content width renders an empty box.
    expect((await small.textContent())?.trim()).not.toBe('')
  })

  test('StatCard tints both its label and its value when a tone is set', async ({ page }) => {
    await page.route('**/api/prod-readiness', route =>
      route.fulfill({ status: 200, body: JSON.stringify(READINESS) }),
    )
    await page.goto('/system/readiness')

    // Anchor on the LABEL element with an exact-match regex: a plain `hasText: 'GO'` is a
    // substring match, so it also selects the "NO-GO" card and the locator resolves to two
    // elements under strict mode.
    const colourOf = (label: string) =>
      page
        .locator('.stat-card')
        .filter({ has: page.locator('.stat-label', { hasText: new RegExp(`^${label}$`) }) })
        .locator('.stat-value')
        .evaluate(el => getComputedStyle(el).color)

    // GO and NO-GO carry a verdict, so their VALUES must differ in colour from each other
    // and from the untinted tile. Tinting only the label was regression (3).
    const [go, nogo] = [await colourOf('GO'), await colourOf('NO-GO')]
    expect(go).not.toBe(nogo)

    const plain = await colourOf('Services')
    expect(plain).not.toBe(go)
  })

  test('PageHeader renders its icon slot', async ({ page }) => {
    await page.route('**/api/prod-readiness', route =>
      route.fulfill({ status: 200, body: JSON.stringify(READINESS) }),
    )
    await page.goto('/system/readiness')

    // Regression (2): the icon was silently dropped when the page moved to PageHeader.
    await expect(page.locator('.page-header svg').first()).toBeVisible()
  })

  test('StatusBadge distinguishes ACTIVE from REVOKED by tone, not by text alone', async ({ page }) => {
    await page.route('**/api/svc/consent-service/api/v1/consents/grantee/**', route =>
      route.fulfill({ status: 200, body: JSON.stringify(CONSENTS) }),
    )
    await page.goto('/consents')

    // The grantee lens has to be selected first. #2568 wrote this test when /consents had a
    // single grantee-id field; #2604 added the party/grantee lens selector and made `party` the
    // default, so the "Look up" button this test clicks is no longer rendered on load — the
    // click waited 30 s for a locator that never resolves and every admin-ui PR went red.
    // Selecting the lens is not a workaround: the mocked route below IS the grantee endpoint,
    // so this is the flow the test was always asserting about.
    await page.getByRole('combobox').selectOption('grantee')
    // Selecting `grantee` seeds the input with MARKETING_GRANTEE, so the button is enabled;
    // asserting that before clicking keeps a future `disabled` regression from re-reading as
    // this same 30 s timeout.
    const lookUp = page.getByRole('button', { name: /Vyhledat|Look up/ })
    await expect(lookUp).toBeEnabled()
    await lookUp.click()

    const active = page.locator('.badge', { hasText: /^ACTIVE$/ })
    const revoked = page.locator('.badge', { hasText: /^REVOKED$/ })
    await expect(active).toBeVisible()
    await expect(revoked).toBeVisible()

    // statusTone maps ACTIVE -> success and REVOKED -> neutral (the consent reading: a
    // withdrawal under GDPR Art. 7(3) is a right being exercised, not an incident). If the
    // vocabulary ever collapses to one tone, an operator loses the distinction at a glance.
    const bg = (l: typeof active) => l.evaluate(el => getComputedStyle(el).backgroundColor)
    expect(await bg(active)).not.toBe(await bg(revoked))
  })
})
