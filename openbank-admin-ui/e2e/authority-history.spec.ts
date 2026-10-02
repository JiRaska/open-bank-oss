// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const id = '11111111-1111-4111-8111-111111111111'
const at = '2026-09-17T12:00:00Z'
const history = {
  root: `delegation:${id}`, effectiveAt: at, knownAt: at, truncated: false, actionAuthorization: 'UNKNOWN',
  observations: [2, 1].map(revision => ({
    evidence: {
      delegationId: id, revision, eventType: revision === 2 ? 'DelegationRevoked' : 'DelegationActivated',
      grantorPartyId: '22222222-2222-4222-8222-222222222222', granteePartyId: '33333333-3333-4333-8333-333333333333',
      resourceType: 'ACCOUNT', resourceId: '44444444-4444-4444-8444-444444444444', capabilities: ['ACCOUNT_READ'],
      approvalPolicy: 'SOLO', requiredApprovals: null, validFrom: '2026-09-16T00:00:00Z', validTo: null,
      occurredAt: `2026-09-17T0${revision}:00:00Z`,
    },
    recordedAt: at, evidenceRef: `delegation:${id}:${revision}`, contentHash: String(revision).repeat(64),
  })),
}

test('reviews source authority history and isolates scope on desktop and mobile', async ({ page, context, baseURL }, testInfo) => {
  await signInWithRoles(context, baseURL!, ['ROLE_COMPLIANCE'])
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'en'))
  await page.setViewportSize({ width: 1440, height: 1080 })
  let denied = false
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.startsWith('/api/auth/')) return route.continue()
    if (url.pathname === `/api/context/authorizations/${id}`) {
      return denied ? route.fulfill({ status: 403, json: { error: 'forbidden' } }) : route.fulfill({ json: history })
    }
    return route.fulfill({ status: 503, json: { error: 'Synthetic unrelated source unavailable' } })
  })
  await page.goto('/audit')
  const region = page.getByRole('region', { name: 'Delegation evidence history' })
  await expect(region).toBeVisible()
  await region.getByLabel('Delegation ID', { exact: true }).fill(id)
  await region.getByLabel('Assigned case ID', { exact: true }).fill('case-synthetic')
  await region.getByRole('button', { name: 'Load history' }).click()
  const graph = region.getByRole('img', { name: 'Delegation observation graph' })
  await expect(graph).toBeVisible()
  const revoked = region.getByRole('button', { name: /DelegationRevoked/ })
  const activated = region.getByRole('button', { name: /DelegationActivated/ })
  await expect(revoked).toHaveAttribute('aria-pressed', 'true')
  await activated.click()
  await expect(activated).toHaveAttribute('aria-pressed', 'true')
  await expect(revoked).toHaveAttribute('aria-pressed', 'false')
  await expect(region.getByText(/Authorization of a specific business action: UNKNOWN/)).toBeVisible()
  await region.screenshot({ path: testInfo.outputPath('authority-history-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('complementary', { name: 'Main navigation' })).toBeHidden()
  // A tall capture exposes the complete card inside the app's scrolling main pane.
  await page.setViewportSize({ width: 390, height: 1500 })
  await region.screenshot({ path: testInfo.outputPath('authority-history-mobile.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await testInfo.attach('mobile-region-layout', { body: JSON.stringify(await region.evaluate(element => ({ width: element.clientWidth, scrollWidth: element.scrollWidth, viewport: document.documentElement.clientWidth, left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().right }))), contentType: 'application/json' })
  await expect.poll(() => region.evaluate(element => {
    const bounds = element.getBoundingClientRect()
    return bounds.left >= 0 && bounds.right <= document.documentElement.clientWidth && element.scrollWidth <= element.clientWidth + 1
  })).toBe(true)
  await region.getByLabel('Assigned case ID', { exact: true }).fill('case-other')
  await expect(graph).toHaveCount(0)
  await expect(revoked).toHaveCount(0)
  denied = true
  await region.getByRole('button', { name: 'Load history' }).click()
  await expect(region.getByRole('alert')).toHaveText('Access to this case was not permitted.')
  await expect(graph).toHaveCount(0)
  await expect(region.getByRole('list', { name: 'Observation timeline' })).toHaveCount(0)
})
