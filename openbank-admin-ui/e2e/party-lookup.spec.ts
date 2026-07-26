// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// E2E for the party-name lookup on /customer-360 and /consents (ADR-0210 D8).
//
// These exist because of a defect that NO unit test could see and that the page's own code comment
// wrongly claimed to prevent. Clearing results when the lens changes handles the settled case only —
// an already in-flight request settles afterwards and puts them straight back. Measured with a
// 1.5s-delayed party lookup interrupted by a lens switch: the <select> read "By grantee" while
// MARKETING_COMMS_EMAIL rows from the PARTY query sat on screen. On a consents page that is one
// customer's data presented as a global marketing view.
//
// Only a real browser with real timing can express that. The assertions below are the ones that were
// RED before the generation-counter guard and green after it — the fix's own regression net.

import { test, expect } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const A = { id: '05a02ef1-381c-40e7-b73f-d6855eead42e', legalName: 'Jan Novák', email: 'jan@example.test', status: 'ACTIVE', kycStatus: 'VERIFIED' }
const B = { id: 'ec28276d-28b9-4cdb-ac03-097afca855b9', legalName: 'Nováková Petra', email: 'petra@example.test', status: 'ACTIVE', kycStatus: 'PENDING' }

const A_360 = {
  available: true, partyId: A.id, asOf: '2026-07-26 09:14:22', partyState: {}, accountIds: ['acc-1'],
  domains: [{ aggregateType: 'party', events: 12, lastEventType: 'PARTY_UPDATED', lastOccurredAt: '2026-07-26 09:14:22' }],
  consents: [],
}
// A party that exists but has no projected events: available (ClickHouse answered), zero domains.
const B_360 = { available: true, partyId: B.id, asOf: null, partyState: null, domains: [], accountIds: [], consents: [] }

const A_CONSENTS = [{
  id: 'c-1', partyId: A.id, granteeId: 'party-service:marketing-comms', granteeType: 'INTERNAL',
  granteeName: 'Marketing', scopes: ['MARKETING_COMMS_EMAIL'], accountIbans: null, status: 'ACTIVE',
  validFrom: '2026-01-01', validTo: '2027-01-01', createdAt: '2026-01-01',
}]

async function stubSearch(page: import('@playwright/test').Page) {
  await page.route('**/parties/search**', r =>
    r.fulfill({ status: 200, body: JSON.stringify({ data: [A, B] }) }))
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test.describe('party-name lookup (ADR-0210 D8)', () => {
  test('resolves a NAME to a party and opens its 360 — no UUID typed anywhere', async ({ page }) => {
    await stubSearch(page)
    await page.route(`**/api/customer-360/${A.id}`, r => r.fulfill({ status: 200, body: JSON.stringify(A_360) }))

    await page.goto('/customer-360')
    await page.getByRole('textbox').fill('Novák')
    await page.getByRole('button', { name: /Vyhledat|Search/ }).click()

    await expect(page.getByText('Jan Novák')).toBeVisible()
    await page.getByRole('button', { name: /Vybrat|Select/ }).first().click()
    await expect(page.getByText(/Domény a aktuálnost|Domains and recency/)).toBeVisible()
  })

  test('a party with no events says so, and never that the source is empty', async ({ page }) => {
    await stubSearch(page)
    await page.route(`**/api/customer-360/${B.id}`, r => r.fulfill({ status: 200, body: JSON.stringify(B_360) }))

    await page.goto('/customer-360')
    await page.getByRole('textbox').fill('Novák')
    await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
    await page.getByRole('button', { name: /Vybrat|Select/ }).nth(1).click()

    await expect(page.getByText(/nemá žádné analytické události|has no analytics events/)).toBeVisible()
    // The copy that made a working page read as broken. It described the SOURCE, not the query.
    await expect(page.getByText(/neobsahuje žádné záznamy|does not contain any records/)).toHaveCount(0)
  })

  test('/consents defaults to the party lens, so an operator lands on name search', async ({ page }) => {
    await stubSearch(page)
    await page.goto('/consents')
    await expect(page.getByRole('combobox')).toHaveValue('party')
    await expect(page.getByPlaceholder(/Jméno nebo název|Name or company/)).toBeVisible()
  })

  test('a lens switch invalidates an IN-FLIGHT lookup, not just the settled rows', async ({ page }) => {
    await stubSearch(page)
    // Slow enough that the lens switch lands first and the response second — the exact interleaving
    // that a synchronous state clear cannot defend against.
    await page.route('**/consents/party/**', async r => {
      await new Promise(res => setTimeout(res, 1500))
      await r.fulfill({ status: 200, body: JSON.stringify(A_CONSENTS) })
    })

    await page.goto('/consents')
    await page.getByRole('textbox').fill('Novák')
    await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
    await expect(page.getByText('Jan Novák')).toBeVisible()
    await page.getByRole('button', { name: /Vybrat|Select/ }).first().click()

    await page.waitForTimeout(200) // request in flight
    await page.getByRole('combobox').selectOption('grantee')
    await page.waitForTimeout(2000) // long enough for the superseded response to have landed

    await expect(page.getByRole('combobox')).toHaveValue('grantee')
    // The party query's rows must NOT appear under the grantee lens. This was RED before the fix.
    await expect(page.getByText('MARKETING_COMMS_EMAIL')).toHaveCount(0)
    await expect(page.getByText(/Nalezeno|Found/)).toHaveCount(0)
  })
})
