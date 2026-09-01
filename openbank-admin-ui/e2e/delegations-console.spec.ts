// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0076 Layer 2 — the delegation console end-to-end (ADR-0230 / ADR-0232).
//
// Four properties are worth an e2e rather than a route test:
//   1. the grant list renders from a REAL BFF response (the page, its own route handler and the
//      upstream shape agreeing — a route test proves only the middle one),
//   2. party lookup goes through the ADR-0228 entity-resolution facade, never a UUID field,
//   3. there is NO direct mutation path — asserted by watching the wire, not by reading source.
//   4. a grant detail joins the live grant with its narrow, payload-free audit projection.
//
// Only the CLUSTER hop is stubbed (page.route intercepts the browser's call to admin-ui's own
// BFF); the BFF handlers themselves are the real ones running in `next dev`.

import { test, expect, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY = '018f4a3c-1b2d-7e00-9a11-000000000001'
const PARTY_B = '018f4a3c-1b2d-7e00-9a11-00000000000b'
const GRANTEE = '018f4a3c-1b2d-7e00-9a11-0000000000ff'
const GRANT = '018f4a3c-1b2d-7e00-9a11-000000000002'
const RESOURCE = '018f4a3c-1b2d-7e00-9a11-000000000003'
const ROLE_PRESET = {
  id: '018f4a3c-1b2d-7e00-9a11-000000000004',
  name: 'Účetní',
  description: 'Čte zůstatky a historii účtu.',
  resourceType: 'ACCOUNT',
  capabilities: ['ACCOUNT_READ_BALANCES', 'ACCOUNT_INITIATE_PAYMENT'],
}

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

const AUDIT_TIMELINE = {
  grantId: GRANT,
  latestStatusAfter: 'ACTIVE',
  mayBeTruncated: false,
  entries: [{
    evidenceId: '018f4a3c-1b2d-7e00-9a11-000000000101',
    eventType: 'DelegationActivated',
    occurredAt: '2026-01-01T00:00:00Z',
    recordedAt: '2026-01-01T00:00:01Z',
    timeSource: 'event',
    actorId: null,
    actorType: null,
    actorProvenance: 'absent',
    reason: null,
    reasonState: 'not_recorded',
    reasonTruncated: false,
    statusAfter: 'ACTIVE',
    sourceService: 'delegation-service',
    sourceAttribution: 'event',
    correlationId: 'e2e-correlation',
  }],
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
    if (path.includes('/effective-access/')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          evaluatedAt: '2026-09-01T12:00:00Z',
          nextChangeAt: null,
          refreshAfterMs: null,
          accounts: [{ id: RESOURCE, nickname: 'Provozní účet', accountNumber: 'CZ1234567890', currencyCode: 'CZK', status: 'ACTIVE' }],
          cards: [],
          grants: [],
          presets: [ROLE_PRESET],
          resourceDetails: [],
          sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
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
    if (path.endsWith(`/${GRANT}/audit`)) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(AUDIT_TIMELINE) })
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
  // Keep the fixture language explicit: the display is intentionally locale-aware now,
  // while this assertion exercises the Czech copy and formatting contract.
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'cs'))
  const seen = await stubBff(page)
  await page.goto('/delegations')

  // ADR-0231: the operator types a NAME. There is no UUID field on this screen.
  await page.getByRole('textbox', { name: /Hledat stranu|Search party/ }).fill('Novák')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()

  await page.getByRole('button', { name: /Jan Novák/ }).click()

  const main = page.locator('main')
  await expect(main.getByText('Účetní').first()).toBeVisible()
  await expect(main.getByText('Provozní účet').first()).toBeVisible()
  await expect(main.getByText('Zůstatky').first()).toBeVisible()
  await expect(main.getByText('Provést platbu').first()).toBeVisible()
  await expect(main.getByText('5 000 CZK').first()).toBeVisible()
  await expect(main.getByText('bez limitu').first()).toBeVisible()
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

test('a slow previous selection can never overwrite the newest customer', async ({ page }) => {
  await page.route('**/api/entities/resolve**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      results: [
        { type: 'party', id: PARTY, label: 'Klient A' },
        { type: 'party', id: PARTY_B, label: 'Klient B' },
      ],
    }),
  }))
  await page.route('**/api/delegations/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/projection-health')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consumers: [], state: 'ok' }) })
    }
    const selectedParty = path.includes(PARTY_B) ? PARTY_B : PARTY
    if (selectedParty === PARTY) await new Promise(resolve => setTimeout(resolve, 350))
    if (path.includes('/effective-access/')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          evaluatedAt: '2026-09-01T12:00:00Z',
          nextChangeAt: null,
          refreshAfterMs: null,
          accounts: [{ id: `${selectedParty}-account`, nickname: selectedParty === PARTY_B ? 'Účet klienta B' : 'Účet klienta A' }],
          cards: [],
          grants: [],
          presets: [ROLE_PRESET],
          resourceDetails: [],
          sources: { accounts: 'ok', cards: 'ok', grants: 'ok', presets: 'ok' },
        }),
      })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ partyId: selectedParty, granted: [], received: [], sources: { granted: 'ok', received: 'ok' } }),
    })
  })

  await page.goto('/delegations')
  await page.getByRole('textbox', { name: /Hledat stranu|Search party/ }).fill('Klient')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
  await page.getByRole('button', { name: /Klient A/ }).click()
  await page.getByRole('button', { name: /Klient B/ }).click()

  const main = page.locator('main')
  await expect(main.getByText('Účet klienta B')).toBeVisible()
  await expect(main.getByText('Účet klienta A')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Klient B/ })).toHaveAttribute('aria-pressed', 'true')
})

test('a newer search stays available and wins over a slower previous query', async ({ page }) => {
  let slowSearchFinished = false
  await page.route('**/api/entities/resolve**', async route => {
    const query = new URL(route.request().url()).searchParams.get('q')
    if (query === 'alfa') {
      await new Promise(resolve => setTimeout(resolve, 800))
      slowSearchFinished = true
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        results: [{ type: 'party', id: query === 'beta' ? PARTY_B : PARTY, label: query === 'beta' ? 'Výsledek Beta' : 'Výsledek Alfa' }],
      }),
    })
  })

  await page.goto('/delegations')
  const input = page.getByRole('textbox', { name: /Hledat stranu|Search party/ })
  const button = page.getByRole('button', { name: /Vyhledat|Search/ })
  await input.fill('alfa')
  await button.click()
  await input.fill('beta')
  await expect(button).toBeEnabled()
  await button.click()

  await expect(page.getByRole('button', { name: /Výsledek Beta/ })).toBeVisible()
  await expect.poll(() => slowSearchFinished).toBe(true)
  await expect(page.getByRole('button', { name: /Výsledek Alfa/ })).toHaveCount(0)
})

test('a failed effective summary does not hide successfully loaded grants', async ({ page }) => {
  await stubBff(page)
  await page.unroute('**/api/delegations/**')
  let releaseProjection = () => {}
  const projectionGate = new Promise<void>(resolve => { releaseProjection = resolve })
  await page.route('**/api/delegations/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/projection-health')) {
      await projectionGate
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ consumers: [], state: 'ok' }) })
    }
    if (path.includes('/effective-access/')) {
      return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'upstream_unreachable' }) })
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ partyId: PARTY, granted: [GRANT_ROW], received: [], sources: { granted: 'ok', received: 'ok' } }),
    })
  })

  await page.goto('/delegations')
  await page.getByRole('textbox', { name: /Hledat stranu|Search party/ }).fill('Novák')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
  await page.getByRole('button', { name: /Jan Novák/ }).click()

  const main = page.locator('main')
  await expect(main.getByText(/Souhrn efektivního přístupu je dočasně neúplný|effective-access summary is temporarily incomplete/)).toBeVisible()
  await expect(main.getByTitle('ACCOUNT_INITIATE_PAYMENT').first()).toBeVisible()
  await expect(main.getByRole('link', { name: /Detail/ }).first()).toBeVisible()
  await expect(main.getByText(/Načítám zdraví projekcí|Loading projection health/)).toBeVisible()
  releaseProjection()
  await expect(main.getByText(/nemá žádnou konzumentskou skupinu|has no consumer group/)).toBeVisible()
})

test('role deletion explains impact and recovers from a failed request before removing the preset', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'cs'))
  await stubBff(page)

  let roles = [ROLE_PRESET]
  let deleteAttempts = 0
  await page.route('**/api/delegation-role-presets**', route => {
    const request = route.request()
    if (request.method() === 'DELETE') {
      deleteAttempts += 1
      if (deleteAttempts === 1) {
        return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
      }
      roles = []
      return route.fulfill({ status: 204, body: '' })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(roles) })
  })

  await page.goto('/delegations')
  await expect(page.getByRole('heading', { name: ROLE_PRESET.name, exact: true })).toBeVisible()
  await page.getByRole('button', { name: `Smazat ${ROLE_PRESET.name}` }).click()

  const dialog = page.getByRole('alertdialog', { name: `Smazat roli „${ROLE_PRESET.name}“?` })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('Již udělená práva se nezmění ani neodvolají.')

  await dialog.getByRole('button', { name: 'Smazat preset' }).click()
  await expect(dialog.getByRole('alert')).toContainText('Nic se nezměnilo; zkuste to znovu.')
  await expect(page.getByRole('heading', { name: ROLE_PRESET.name, exact: true })).toBeVisible()

  await dialog.getByRole('button', { name: 'Smazat preset' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('heading', { name: ROLE_PRESET.name, exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Dispoziční role a práva' })).toBeFocused()
  expect(deleteAttempts).toBe(2)
})

test('role creation stays editable after failure and retries without a duplicate submission', async ({ page }) => {
  await page.addInitScript(() => window.localStorage.setItem('openbank-admin-lang', 'cs'))
  await stubBff(page)

  let roles: typeof ROLE_PRESET[] = []
  let createAttempts = 0
  await page.route('**/api/delegation-role-presets**', async route => {
    const request = route.request()
    if (request.method() === 'POST') {
      createAttempts += 1
      if (createAttempts === 1) {
        return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'temporarily unavailable' }) })
      }
      const payload = request.postDataJSON() as Omit<typeof ROLE_PRESET, 'id'>
      roles = [{ ...payload, id: ROLE_PRESET.id }]
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(roles[0]) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(roles) })
  })

  await page.goto('/delegations')
  await page.getByRole('button', { name: 'Přidat roli' }).click()
  const editor = page.getByRole('dialog', { name: 'Nastavení dispoziční role' })
  await editor.getByRole('textbox', { name: 'Název' }).fill(ROLE_PRESET.name)
  await editor.getByRole('textbox', { name: 'Popis' }).fill(ROLE_PRESET.description)
  await editor.getByRole('checkbox').first().check()

  await editor.getByRole('button', { name: 'Uložit' }).click()
  await expect(editor.getByRole('alert')).toContainText('Nic se nezměnilo; zkontrolujte údaje a zkuste to znovu.')
  await expect(editor.getByRole('textbox', { name: 'Název' })).toHaveValue(ROLE_PRESET.name)

  await editor.getByRole('button', { name: 'Uložit' }).click()
  await expect(editor).toBeHidden()
  await expect(page.getByRole('heading', { name: ROLE_PRESET.name, exact: true })).toBeVisible()
  expect(createAttempts).toBe(2)
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
  await expect(main.getByRole('heading', { name: /Neměnná auditní časová osa|Immutable audit timeline/ })).toBeVisible()
  await expect(main.getByText(/Delegace přijata a aktivována|Delegation accepted and activated/)).toBeVisible()
  await expect(main.getByText(/odpovídá poslednímu auditnímu přechodu|matches the latest audited transition/)).toBeVisible()
  await expect(main.getByText(/Aktér v události neuveden|Actor not recorded in the event/)).toBeVisible()

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

test('the resource check explains its scope, asks the authority and shows its reason code', async ({ page }) => {
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
  await expect(page.getByText(/Nic nerezervuje|reserves nothing/)).toBeVisible()
  await expect(page.getByText(/konkrétního grantu|specific grant/)).toBeVisible()
  await page.getByRole('textbox', { name: /Částka k ověření|Amount to probe/ }).fill('9000')
  await page.getByRole('button', { name: /^(Ověřit|Probe)$/ }).click()

  const main = page.locator('main')
  await expect(main.getByText(/Zamítnuto|Denied/)).toBeVisible()
  await expect(main.getByText('CEILING_EXCEEDED')).toBeVisible()
  expect(posted).toHaveLength(1)
  expect(JSON.parse(posted[0]).amount).toEqual({ amount: 9000, currency: 'CZK' })
})
