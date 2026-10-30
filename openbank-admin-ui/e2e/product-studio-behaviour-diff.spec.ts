// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// ADR-0352 phase 5b: the approval panel's behavioural diff is the catalog's ProductBehaviourDiff, shown as-is.
const SPEC = { id: '10000000-0000-0000-0000-000000000001', code: 'SAV', schemaRef: { id: 'org.openbank.banking.deposit', version: 2 }, createdAt: '2026-10-29T00:00:00Z', revision: 0 }
const OFFERING = { id: '20000000-0000-0000-0000-000000000001', specificationId: SPEC.id, code: 'SAV_CZ', market: {}, revision: 0 }
const REVISION = {
  id: '30000000-0000-0000-0000-000000000001', offeringId: OFFERING.id, number: 2, schemaRef: SPEC.schemaRef, state: 'DRAFT',
  content: { name: { en: 'Savings' }, attributes: {}, prices: [], eligibility: [], relationships: [], documentCodes: [], behaviour: { irVersion: 1, productCode: 'SAV', currency: 'EUR', hooks: {} } },
  makerId: 'maker', contentHash: 'a'.repeat(64), createdAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:00Z',
  effectiveFrom: null, effectiveTo: null, revision: 3,
}
const DIFF = {
  schema: 'openbank.product-behaviour-diff.v1', valid: true, identical: false, changedAccounts: 1,
  revisionA: { productCode: 'SAV', revisionHash: '1'.repeat(64), irVersion: 1 },
  revisionB: { productCode: 'SAV', revisionHash: '2'.repeat(64), irVersion: 1 },
  range: { from: '2026-01-01', to: '2026-03-31', zone: 'UTC', days: 90 }, portfolio: { accounts: 2, seed: null },
  materialityThreshold: '1.000000000000',
  aggregate: { outcome: { a: '1.000000000000', b: '3.500000000000', delta: '2.500000000000' }, posted: [{ ruleId: 'scheduled.capitalise', accountingClass: 'CUSTOMER_DEPOSIT', a: { debit: '0', credit: '1', net: '1.000000000000' }, b: { debit: '0', credit: '3.5', net: '3.500000000000' }, deltaNet: '2.500000000000' }], instructionCounts: [] },
  signChanged: [], thresholdCrossed: ['sample-high-balance'],
  accounts: [{
    account: 'sample-high-balance', outcome: { a: '1.000000000000', b: '3.500000000000', delta: '2.500000000000' },
    finalBalance: { a: '20001.000000000000', b: '20003.500000000000', delta: '2.500000000000' },
    rejectedPostings: { a: 0, b: 0 }, signChanged: false, thresholdCrossed: true, posted: [], instructionCounts: [],
  }],
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const scenario of ['unconserved', 'changed'] as const) {
  test(`behavioural diff panel: ${scenario} replay`, async ({ page }) => {
    const posts: string[] = []
    await page.route('**/api/**', route => {
      const url = new URL(route.request().url())
      if (url.pathname.startsWith('/api/auth/')) return route.continue()
      const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
      if (route.request().method() !== 'GET' && url.pathname.startsWith('/api/svc/')) posts.push(url.pathname)
      if (url.pathname.endsWith('/behaviour-diff')) {
        return json({ diff: scenario === 'changed' ? DIFF : { ...DIFF, valid: false, identical: true }, canonicalJson: '{}' })
      }
      if (url.pathname.endsWith('/api/v2/product-types')) return json([])
      if (url.pathname.endsWith('/api/v2/specifications')) return json([SPEC])
      if (url.pathname.endsWith('/api/v2/offerings')) return json([OFFERING])
      if (url.pathname.endsWith(`/offerings/${OFFERING.id}/revisions`)) return json([REVISION])
      return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
    })

    await page.goto('/product-studio')
    await page.locator('#studio-offering').selectOption(OFFERING.id)
    const panel = page.getByTestId('behaviour-diff-panel')
    await expect(panel).toBeVisible()
    await panel.getByRole('button', { name: /Spustit backtest|Run backtest/ }).click()
    if (scenario === 'unconserved') {
      // Negative first: an unconserved replay is a defect banner, never "no change".
      await expect(panel.getByTestId('behaviour-diff-error')).toBeVisible()
      await expect(panel.getByTestId('behaviour-diff-identical')).toHaveCount(0)
    } else {
      await expect(panel.getByTestId('behaviour-diff-changed')).toContainText('2.500000000000')
      await expect(panel.getByTestId('behaviour-diff-changed')).toContainText('scheduled.capitalise')
      await expect(panel.getByTestId('behaviour-diff-accounts')).toContainText('sample-high-balance')
      await expect(panel.getByTestId('behaviour-diff-threshold')).toContainText('sample-high-balance')
    }
    expect(posts).toEqual([`/api/svc/product-catalog/api/v2/offerings/${OFFERING.id}/revisions/${REVISION.id}/behaviour-diff`])
  })
}
