// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0076 Layer 2 — the delegation console end-to-end (ADR-0230 / ADR-0232).
//
// Three properties are worth an e2e rather than a route test:
//   1. the grant list renders from a REAL BFF response (the page, its own route handler and the
//      upstream shape agreeing — a route test proves only the middle one),
//   2. party lookup goes through the ADR-0228 entity-resolution facade, never a UUID field,
//   3. there is NO direct mutation path — asserted by watching the wire, not by reading source.
//
// Only the CLUSTER hop is stubbed (page.route intercepts the browser's call to admin-ui's own
// BFF); the BFF handlers themselves are the real ones running in `next dev`.

import { test, expect, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY = '018f4a3c-1b2d-7e00-9a11-000000000001'
const GRANTEE = '018f4a3c-1b2d-7e00-9a11-0000000000ff'
const GRANT = '018f4a3c-1b2d-7e00-9a11-000000000002'
const RESOURCE = '018f4a3c-1b2d-7e00-9a11-000000000003'

const GRANT_ROW = {
  id: GRANT,
  grantorPartyId: PARTY,
  granteePartyId: GRANTEE,
  resourceType: 'ACCOUNT',
  resourceId: RESOURCE,
  capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_INITIATE_PAYMENT'],
  approvalPolicy: 'SOLO',
  perTransactionLimit: { amount: 5000, currency: 'CZK' },
  validFrom: '2026-01-01T00:00:00Z',
  validTo: null,
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

/** Stubs admin-ui's own BFF surface. Returns every request path the page actually issued. */
async function stubBff(page: Page): Promise<string[]> {
  const seen: string[] = []

  await page.route('**/api/entities/resolve**', route => {
    seen.push(route.request().url())
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        results: [{ type: 'party', id: PARTY, label: 'Jan Novák', sublabel: 'NATURAL · ACTIVE', route: `/parties/${PARTY}` }],
      }),
    })
  })

  await page.route('**/api/delegations/**', route => {
    const req = route.request()
    seen.push(`${req.method()} ${new URL(req.url()).pathname}`)
    const path = new URL(req.url()).pathname

    if (path.endsWith('/projection-health')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          topic: 'openbank.delegation.events',
          state: 'ok',
          consumers: [{ groupId: 'account-service-delegation', state: 'STABLE', lag: 0, members: 1 }],
        }),
      })
    }
    if (path.includes('/party/')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partyId: PARTY,
          granted: [GRANT_ROW],
          received: [],
          sources: { granted: 'ok', received: 'ok' },
        }),
      })
    }
    // grant detail
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GRANT_ROW) })
  })

  // party-service label resolution behind EntityChip (via the /api/svc proxy).
  await page.route('**/api/svc/party-service/**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ legalName: 'Petr Delegát' }) }),
  )

  return seen
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('finds a party through entity resolution and renders its grants', async ({ page }) => {
  const seen = await stubBff(page)
  await page.goto('/delegations')

  // ADR-0231: the operator types a NAME. There is no UUID field on this screen.
  await page.getByRole('textbox', { name: /Hledat stranu|Search party/ }).fill('Novák')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()

  await page.getByRole('button', { name: /Jan Novák/ }).click()

  const main = page.locator('main')
  await expect(main.getByText('ACCOUNT_READ_BALANCES, ACCOUNT_INITIATE_PAYMENT')).toBeVisible()
  await expect(main.getByText('5 000 CZK')).toBeVisible()
  await expect(main.getByText('ACTIVE').first()).toBeVisible()

  // The grant list came through the console's own BFF route, and party lookup through the
  // shared ADR-0228 facade — not a bespoke search endpoint.
  expect(seen.some(s => s.includes('/api/entities/resolve'))).toBe(true)
  expect(seen.some(s => s === `GET /api/delegations/party/${PARTY}`)).toBe(true)
})

test('a refused direction never renders as "no delegations"', async ({ page }) => {
  await stubBff(page)
  await page.unroute('**/api/delegations/**')
  await page.route('**/api/delegations/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/projection-health')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consumers: [], state: 'unavailable' }) })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ partyId: PARTY, granted: [], received: [], sources: { granted: 'forbidden', received: 'ok' } }),
    })
  })

  await page.goto('/delegations')
  await page.getByRole('textbox', { name: /Hledat stranu|Search party/ }).fill('Novák')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
  await page.getByRole('button', { name: /Jan Novák/ }).click()

  const main = page.locator('main')
  await expect(main.getByText(/nebyl povolen|was refused for your role/)).toBeVisible()
  // The dangerous wrong answer must NOT be on screen for the refused direction.
  await expect(main.getByText(/Žádné delegace\.|No delegations\./)).toHaveCount(1)
})

test('the grant detail offers NO mutation — suspend, reinstate and revoke are absent from the wire', async ({ page }) => {
  const seen = await stubBff(page)

  // Watch the DELEGATION surface specifically, not every /api/** call. A blanket watch also
  // catches the app's own error-reporting beacon (`POST /api/2/envelope/`, Sentry/GlitchTip),
  // which has nothing to do with delegated access — and a test that fails on unrelated
  // telemetry would get "fixed" by loosening the assertion until it no longer tests anything.
  // The invariant is: nothing this console does may mutate a delegation.
  const mutations: string[] = []
  await page.route('**/api/**', async route => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    const isDelegationSurface = path.includes('/api/delegations') || path.includes('delegation-service')
    if (isDelegationSurface && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(req.method())) {
      mutations.push(`${req.method()} ${path}`)
    }
    await route.fallback()
  })

  await page.goto(`/delegations/${GRANT}`)

  const main = page.locator('main')
  await expect(main.getByText(/Zásahy banky|Bank-side actions/)).toBeVisible()
  await expect(main.getByText(/nejdou|not available from this console/)).toBeVisible()

  // No control anywhere on the page offers the bank-side mutations.
  await expect(page.getByRole('button', { name: /Pozastavit|Suspend/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Obnovit platnost|Reinstate/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Odvolat|Revoke/ })).toHaveCount(0)

  // And nothing was written on load. The ONLY non-GET this console may ever issue is the
  // side-effect-free coverage probe, which needs an explicit click.
  expect(mutations).toEqual([])
  expect(seen.some(s => s.includes('/suspend') || s.includes('/reinstate'))).toBe(false)

  // Known-positive: prove the watcher above can actually SEE a delegation mutation. Without
  // this, narrowing its filter to the delegation surface could have made it match nothing, and
  // an empty `mutations` array would mean "the probe is broken", not "the console is clean" —
  // indistinguishable from the assertion passing for the right reason.
  await page.evaluate(() =>
    fetch('/api/delegations/00000000-0000-0000-0000-000000000000/suspend', { method: 'POST' }).catch(() => {}),
  )
  await expect.poll(() => mutations).toEqual([
    'POST /api/delegations/00000000-0000-0000-0000-000000000000/suspend',
  ])
})

test('the coverage probe asks the authority and shows its reason code', async ({ page }) => {
  await stubBff(page)
  await page.unroute('**/api/delegations/**')

  const posted: string[] = []
  await page.route('**/api/delegations/**', route => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    if (req.method() === 'POST' && path.endsWith('/check')) {
      posted.push(String(req.postData()))
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ granted: false, reason: 'per-transaction ceiling exceeded', code: 'CEILING_EXCEEDED' }),
      })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(GRANT_ROW) })
  })

  await page.goto(`/delegations/${GRANT}`)
  await page.getByRole('textbox', { name: /Částka k ověření|Amount to probe/ }).fill('9000')
  await page.getByRole('button', { name: /^(Ověřit|Probe)$/ }).click()

  const main = page.locator('main')
  await expect(main.getByText(/Zamítnuto|Denied/)).toBeVisible()
  await expect(main.getByText('CEILING_EXCEEDED')).toBeVisible()
  expect(posted).toHaveLength(1)
  expect(JSON.parse(posted[0]).amount).toEqual({ amount: 9000, currency: 'CZK' })
})
